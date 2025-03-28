package com.example.llmn.domain.user;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.openai.OpenAiKeyService;
import com.example.llmn.domain.user.model.request.*;
import com.example.llmn.domain.user.model.response.*;
import com.example.llmn.integration.email.model.EmailVerificationTemplate;
import com.example.llmn.security.JWTProvider;
import com.example.llmn.integration.email.EmailService;
import com.example.llmn.domain.metric.MetricRepository;
import com.example.llmn.domain.remote.SshInfo;
import com.example.llmn.domain.alarm.AlarmRepository;
import com.example.llmn.domain.project.ProjectRepository;
import com.example.llmn.domain.remote.SecureShellManager;
import com.example.llmn.domain.remote.SshInfoRepository;
import com.example.llmn.domain.summary.SummaryRepository;
import com.example.llmn.integration.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.example.llmn.common.constants.GlobalConstants.DELIMITER;
import static com.example.llmn.common.constants.GlobalConstants.PREFIX_CODE;
import static com.example.llmn.integration.email.EmailConstants.*;
import static com.example.llmn.integration.email.EmailService.generateVerificationCode;
import static com.example.llmn.integration.email.MailTemplate.VERIFICATION_CODE;
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
    private final OpenAiKeyService openAiKeyService;
    private final SecureShellManager secureShellManager;
    private final RedisService redisService;
    private final SummaryRepository summaryRepository;
    private final EmailService emailService;
    private final UserValidator userValidator;

    @Transactional
    public void join(JoinReq requestDTO) {
        userValidator.validateJoinRequest(requestDTO);

        User user = saveUser(requestDTO);

        List<SshInfo> sshInfos = secureShellManager.createSshConfigurations(requestDTO.sshInfos(), user);
        secureShellManager.setMonitoringTarget(requestDTO.monitoringSshHost(), sshInfos, user);

        openAiKeyService.saveOpenAIKey(requestDTO.openAiKey(), user);
    }

    @Async
    public void sendCodeByEmail(String email, String codeType) {
        userValidator.validateAlreadySendCode(email, codeType);

        String verificationCode = generateVerificationCode();
        storeVerificationCode(email, codeType, verificationCode);

        EmailVerificationTemplate templateModel = new EmailVerificationTemplate(verificationCode);
        emailService.sendMail(email, VERIFICATION_CODE.getSubject(), MAIL_TEMPLATE_FOR_CODE, templateModel);
    }

    @Transactional
    public void updateConfiguration(UpdateConfigurationReq requestDTO, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        userValidator.validateSshHostsUnique(requestDTO.sshInfos());
        userValidator.validateSshInfosNotEmpty(requestDTO.sshInfos());
        userValidator.validateMonitoringSshHostSelected(requestDTO);

        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
        secureShellManager.updateExistingSshConfigurations(sshInfos, requestDTO.sshInfos());
        List<SshInfo> newSshHosts = secureShellManager.addNewSshConfigurations(sshInfos, requestDTO.sshInfos(), user);

        SshInfo monitoringSshInfo = findMonitoringSshInfo(sshInfos, newSshHosts, requestDTO.monitoringSshHost());
        user.updateConfiguration(requestDTO.nickName(), requestDTO.receivingAlarm(), monitoringSshInfo.getId());

        cacheUserSshInfo(userId, monitoringSshInfo);
    }

    @Transactional
    public void updateMonitoringSsh(Long userId, String monitoringSshHost) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        SshInfo monitoringSshInfo = secureShellManager.findMonitoringSshConfig(userId, monitoringSshHost);
        user.updateMonitoringSshInfo(monitoringSshInfo.getId());
    }

    @Transactional
    public void resetPassword(ResetPasswordReq requestDTO) {
        String email = getEmailByVerificationCode(requestDTO);
        if (email == null)
            throw new CustomException(ExceptionCode.BAD_APPROACH);

        redisService.removeValue(REDIS_KEY_CODE_TO_EMAIL, requestDTO.code());
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

    public CheckAccountExistRes checkAccountExist(EmailReq requestDTO) {
        boolean isValid = userRepository.findByEmail(requestDTO.email()).isPresent();
        return new CheckAccountExistRes(isValid);
    }

    public VerifySshConnectRes checkSshConnect(VerifySshConnectReq requestDTO) {
        boolean isValid = secureShellManager.testSshConnection(requestDTO.remoteHost(), requestDTO.remoteName(), requestDTO.remoteKeyPath());
        return new VerifySshConnectRes(isValid);
    }

    public Long checkAccessToken(String accessToken) {
        if (JWTProvider.isInvalidJwtFormat(accessToken))
            throw new CustomException(ExceptionCode.TOKEN_WRONG);

        return JWTProvider.extractUserIdFromToken(accessToken);
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

        return userRepository.save(user);
    }

    private void storeVerificationCode(String email, String codeType, String verificationCode) {
        redisService.storeValue(getCodeTypeKey(codeType), email, verificationCode, REDIS_EXP_VERIFICATION_CODE_MS);
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

    private String getEmailByVerificationCode(ResetPasswordReq requestDTO) {
        return redisService.getValueInString(REDIS_KEY_CODE_TO_EMAIL, requestDTO.code());
    }

    private void updatePassword(String email, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_EMAIL_NOT_FOUND));

        user.updatePassword(passwordEncoder.encode(newPassword));
    }

    private void cacheVerificationInfoIfRecovery(VerifyCodeReq requestDTO, String codeType) {
        if (codeType.equals(CODE_TYPE_RECOVERY))
            redisService.storeValue(REDIS_KEY_CODE_TO_EMAIL, requestDTO.code(), requestDTO.email(), 5 * 60 * 1000L);
    }

    private String getCodeTypeKey(String codeType) {
        return PREFIX_CODE + codeType;
    }
}