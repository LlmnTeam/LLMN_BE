package com.example.llmn.domain.user;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.user.model.request.*;
import com.example.llmn.domain.user.model.response.*;
import com.example.llmn.security.JWTProvider;
import com.example.llmn.integration.email.EmailService;
import com.example.llmn.domain.metric.MetricRepository;
import com.example.llmn.domain.remote.SshInfo;
import com.example.llmn.domain.alarm.AlarmRepository;
import com.example.llmn.domain.project.ProjectRepository;
import com.example.llmn.domain.remote.SSHService;
import com.example.llmn.domain.remote.SshInfoRepository;
import com.example.llmn.domain.summary.SummaryRepository;
import com.example.llmn.integration.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

import static com.example.llmn.common.constants.GlobalConstants.DELIMITER;
import static com.example.llmn.common.constants.GlobalConstants.PREFIX_CODE;
import static com.example.llmn.integration.email.EmailConstants.MAIL_TEMPLATE_FOR_CODE;
import static com.example.llmn.integration.email.EmailService.generateVerificationCode;
import static com.example.llmn.integration.email.MailTemplate.VERIFICATION_CODE;
import static com.example.llmn.common.utils.UriUtils.buildURI;
import static com.example.llmn.integration.redis.RedisConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final SshInfoRepository sshInfoRepository;
    private final MetricRepository metricRepository;
    private final AlarmRepository alarmRepository;
    private final SSHService sshService;
    private final RedisService redisService;
    private final SummaryRepository summaryRepository;
    private final WebClient webClient;
    private final EmailService mailUtils;
    private final UserValidator userValidator;

    @Value("${validate_key.uri}")
    private String requestValidateKeyUri;

    @Value("${update_env.uri}")
    private String updateEnvUri;

    private static final String MODEL_KEY_CODE = "code";
    private static final String OPEN_API_KEY = "OPENAI_API_KEY";
    private static final String CODE_TO_EMAIL_KEY_PREFIX = "codeToEmail";
    private static final String CODE_TYPE_RECOVERY = "recovery";
    private static final long VERIFICATION_CODE_EXPIRATION_MS = 175 * 1000L;

    @Transactional
    public void join(JoinReq requestDTO) {
        userValidator.validateJoinRequest(requestDTO);

        User user = saveUser(requestDTO);

        List<SshInfo> sshInfos = sshService.saveSshInfos(requestDTO.sshInfos(), user);
        setMonitoringSshInfo(requestDTO.monitoringSshHost(), sshInfos, user);

        updateApiKey(requestDTO.openAiKey());
    }

    @Async
    public void sendCodeByEmail(String email, String codeType) {
        userValidator.validateAlreadySendCode(email, codeType);

        String verificationCode = generateVerificationCode();
        storeVerificationCode(email, codeType, verificationCode);

        Map<String, Object> templateModel = createTemplateModel(verificationCode);
        mailUtils.sendMail(email, VERIFICATION_CODE.getSubject(), MAIL_TEMPLATE_FOR_CODE, templateModel);
    }

    @Transactional
    public void updateConfiguration(UpdateConfigurationReq requestDTO, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        userValidator.validateNoDuplicateSshHosts(requestDTO.sshInfos());
        userValidator.validateIsSshHostsEmpty(requestDTO.sshInfos());
        userValidator.validateMonitoringSshHostSelected(requestDTO);

        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
        sshService.updateStoredSshInfo(sshInfos, requestDTO.sshInfos());
        List<SshInfo> newSshHosts = sshService.saveNewSshInfos(sshInfos, requestDTO.sshInfos(), user);

        SshInfo monitoringSshInfo = findMonitoringSshInfo(sshInfos, newSshHosts, requestDTO.monitoringSshHost());
        user.updateConfiguration(requestDTO.nickName(), requestDTO.receivingAlarm(), monitoringSshInfo.getId());

        cacheUserSshInfo(userId, monitoringSshInfo);
    }

    @Transactional
    public void updateMonitoringSsh(Long userId, String monitoringSshHost) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        SshInfo monitoringSshInfo = findMonitoringSshInfo(userId, monitoringSshHost);
        user.updateMonitoringSshInfoId(monitoringSshInfo.getId());
    }

    public void updateApiKey(String value) {
        EnvUpdateDTO responseDTO = webClient.post()
                .uri(buildURI(updateEnvUri))
                .bodyValue(new EnvUpdateReq(OPEN_API_KEY, value))
                .retrieve()
                .bodyToMono(EnvUpdateDTO.class)
                .block();

        if (isUpdateSuccess(responseDTO)) {
            throw new CustomException(ExceptionCode.UPDATE_KEY_FAIL);
        }
    }

    @Transactional
    public void resetPassword(ResetPasswordReq requestDTO) {
        String email = getEmailByVerificationCode(requestDTO);
        if (email == null) {
            throw new CustomException(ExceptionCode.BAD_APPROACH);
        }

        redisService.removeValue(CODE_TO_EMAIL_KEY_PREFIX, requestDTO.code());
        updatePassword(email, requestDTO.newPassword());
    }

    @Transactional
    public void withdrawMember(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        alarmRepository.deleteByUserId(userId);
        projectRepository.deleteByUserId(userId);
        summaryRepository.deleteByUserId(userId);
        metricRepository.deleteByUserId(userId);
        sshInfoRepository.deleteByUserId(userId);

        redisService.removeValue(REDIS_KEY_ACCESS_TOKEN, userId.toString());
        redisService.removeValue(REDIS_KEY_REFRESH_TOKEN, userId.toString());

        userRepository.delete(user);
    }

    public VerifyEmailCodeRes verifyCode(VerifyCodeReq requestDTO, String codeType) {
        if (redisService.isNotStoredValue(getCodeTypeKey(codeType), requestDTO.email(), requestDTO.code()))
            return new VerifyEmailCodeRes(false);

        cacheVerificationInfoIfRecovery(requestDTO, codeType);

        return new VerifyEmailCodeRes(true);
    }

    public CheckEmailExistRes checkEmailExist(String email) {
        boolean isValid = userRepository.doesNotExistByEmail(email);
        return new CheckEmailExistRes(isValid);
    }

    public CheckNickNameRes checkNickNameDuplicate(CheckNickReq requestDTO) {
        boolean isDuplicate = userRepository.existsByNickname(requestDTO.nickName());
        return new CheckNickNameRes(isDuplicate);
    }

    public CheckAccountExistRes checkLocalAccountExist(EmailReq requestDTO) {
        boolean isValid = userRepository.findByEmail(requestDTO.email()).isPresent();
        return new CheckAccountExistRes(isValid);
    }

    public VerifySshConnectRes verifySshConnect(VerifySshConnectReq requestDTO) {
        boolean isValid = sshService.checkConnectionValid(requestDTO.remoteHost(), requestDTO.remoteName(), requestDTO.remoteKeyPath());
        return new VerifySshConnectRes(isValid);
    }

    public ValidateOpenAIKeyRes validateOpenAIKey(String apiKey) {
        return webClient.post()
                .uri(buildURI(requestValidateKeyUri))
                .bodyValue(new RequestValidateKeyReq(apiKey))
                .retrieve()
                .bodyToMono(ValidateOpenAIKeyRes.class)
                .block();
    }

    public Long validateAccessTokenInRedis(String accessToken) {
        if (JWTProvider.isInvalidJwtFormat(accessToken)) {
            throw new CustomException(ExceptionCode.TOKEN_WRONG);
        }

        Long userId = JWTProvider.extractUserIdFromToken(accessToken);
        if (redisService.isNotStoredValue(REDIS_KEY_ACCESS_TOKEN, String.valueOf(userId), accessToken))
            throw new CustomException(ExceptionCode.ACCESS_TOKEN_WRONG);

        return userId;
    }

    public ValidateAccessTokenRes findNickName(Long userId) {
        String nickName = userRepository.findNickName(userId).orElse(null);
        return new ValidateAccessTokenRes(nickName);
    }

    public FindCloudInfoRes findCloudInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
        SshInfo selectedSshInfo = sshInfos.stream()
                .filter(sshInfo -> sshInfo.getId().equals(user.getMonitoringSshId()))
                .findFirst()
                .orElseThrow(() -> new CustomException(ExceptionCode.MONITORING_SSH_NOT_FOUND));

        return FindCloudInfoRes.from(sshInfos, selectedSshInfo);
    }

    public FindConfigurationInfoRes findConfigurationInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);

        return FindConfigurationInfoRes.from(user, sshInfos);
    }

    private User saveUser(JoinReq joinRequestDTO) {
        User user = User.builder()
                .nickName(joinRequestDTO.nickName())
                .email(joinRequestDTO.email())
                .password(passwordEncoder.encode(joinRequestDTO.password()))
                .receivingAlarm(joinRequestDTO.receivingAlarm())
                .build();

        userRepository.save(user);

        return user;
    }

    private void setMonitoringSshInfo(String monitoringSshHost, List<SshInfo> sshInfos, User user) {
        Optional<SshInfo> monitoringSshInfo = sshInfos.stream()
                .filter(sshInfo -> sshInfo.getRemoteHost().equals(monitoringSshHost))
                .findFirst();

        if (monitoringSshInfo.isEmpty())
            throw new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT);

        user.updateMonitoringSshInfoId(monitoringSshInfo.get().getId());
    }

    private void storeVerificationCode(String email, String codeType, String verificationCode) {
        redisService.storeValue(getCodeTypeKey(codeType), email, verificationCode, VERIFICATION_CODE_EXPIRATION_MS);
    }

    private Map<String, Object> createTemplateModel(String verificationCode) {
        Map<String, Object> templateModel = new HashMap<>();
        templateModel.put(MODEL_KEY_CODE, verificationCode);
        return templateModel;
    }

    private SshInfo findMonitoringSshInfo(List<SshInfo> sshInfos, List<SshInfo> addedSshHosts, String monitoringSshHost) {
        return sshInfos.stream()
                .filter(sshInfo -> sshInfo.getRemoteHost().equals(monitoringSshHost))
                .findFirst()
                .orElseGet(() -> addedSshHosts.stream()
                        .filter(sshInfo -> sshInfo.getRemoteHost().equals(monitoringSshHost))
                        .findFirst()
                        .orElseThrow(() -> new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT))
                );
    }

    private void cacheUserSshInfo(Long userId, SshInfo monitoringSshInfo) {
        String combinedInfo = String.join(DELIMITER, monitoringSshInfo.getRemoteHost(), monitoringSshInfo.getRemoteName(), monitoringSshInfo.getRemoteKeyPath());
        redisService.storeValue(REDIS_KEY_SSH, userId.toString(), combinedInfo, REDIS_EXP_SSH);
    }

    private SshInfo findMonitoringSshInfo(Long userId, String monitoringSshHost) {
        return sshInfoRepository.findByUserId(userId).stream()
                .filter(sshInfo -> sshInfo.getRemoteHost().equals(monitoringSshHost))
                .findFirst()
                .orElseThrow(() -> new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT));
    }

    private boolean isUpdateSuccess(EnvUpdateDTO responseDTO) {
        return responseDTO == null || !responseDTO.success();
    }

    private String getEmailByVerificationCode(ResetPasswordReq requestDTO) {
        return redisService.getValueInString(CODE_TO_EMAIL_KEY_PREFIX, requestDTO.code());
    }

    private void updatePassword(String email, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_EMAIL_NOT_FOUND));

        user.updatePassword(passwordEncoder.encode(newPassword));
    }

    // 계정 복구 로직에서 요청으로 들어온 코드를 가지고 이메일을 얻기 위해 저장해놓는다 (보안 상 로직)
    private void cacheVerificationInfoIfRecovery(VerifyCodeReq requestDTO, String codeType) {
        if (CODE_TYPE_RECOVERY.equals(codeType))
            redisService.storeValue(CODE_TO_EMAIL_KEY_PREFIX, requestDTO.code(), requestDTO.email(), 5 * 60 * 1000L);
    }

    private String getCodeTypeKey(String codeType) {
        return PREFIX_CODE + codeType;
    }
}