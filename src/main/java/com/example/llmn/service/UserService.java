package com.example.llmn.service;

import com.example.llmn.controller.DTO.UserRequest;
import com.example.llmn.controller.DTO.UserResponse;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.core.security.JWTProvider;
import com.example.llmn.core.utils.EmailUtils;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.domain.User;
import com.example.llmn.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.llmn.core.utils.EmailUtils.generateVerificationCode;
import static com.example.llmn.core.utils.FileUtils.*;
import static com.example.llmn.core.utils.MailTemplate.VERIFICATION_CODE;
import static com.example.llmn.core.utils.UriUtils.buildURI;

@Service
@RequiredArgsConstructor
@Slf4j
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
    private final EmailUtils mailUtils;

    @Value("${validate_key.uri}")
    private String requestValidateKeyUri;

    @Value("${update_env.uri}")
    private String updateEnvUri;

    private static final String PREFIX_CODE = "code:";
    private static final String MAIL_TEMPLATE_FOR_CODE = "verification_code_email.html";
    private static final String SSH_DIRECTORY = "ssh";
    private static final String REDIS_KEY_SESSION_ID = "sessionId";
    private static final String REDIS_KEY_REFRESH_TOKEN = "refreshToken";
    private static final String REDIS_KEY_ACCESS_TOKEN = "accessToken";
    private static final String REDIS_SSH_KEY = "SSH";
    private static final String MODEL_KEY_CODE = "code";
    private static final String DELIMITER = "-";
    private static final Long REDIS_SSH_KEY_EXP = 60L * 60 * 24 * 180; // 180일
    private static final String OPEN_API_KEY = "OPENAI_API_KEY";
    private static final String CODE_TO_EMAIL_KEY_PREFIX = "codeToEmail";
    private static final String CODE_TYPE_RECOVERY = "recovery";
    private static final long VERIFICATION_CODE_EXPIRATION_MS = 175 * 1000L;

    @Transactional
    public Map<String, String> login(UserRequest.LoginDTO requestDTO) {
        User user = userRepository.findByEmail(requestDTO.email()).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_ACCOUNT_WRONG)
        );

        if (isPasswordMatched(requestDTO.password(), user.getPassword())) {
            throw new CustomException(ExceptionCode.USER_ACCOUNT_WRONG);
        }

        return createToken(user);
    }

    @Transactional
    public void join(UserRequest.JoinDTO requestDTO) {
        validateJoinRequest(requestDTO);

        User user = saveUser(requestDTO);

        List<SshInfo> sshInfos = saveSshInfos(requestDTO.sshInfos(), user);
        setMonitoringSshInfo(requestDTO.monitoringSshHost(), sshInfos, user);

        updateApiKey(requestDTO.openAiKey());
    }

    @Async
    public void sendCodeByEmail(String email, String codeType) {
        checkAlreadySendCode(email, codeType);

        String verificationCode = generateVerificationCode();
        storeVerificationCode(email, codeType, verificationCode);

        Map<String, Object> templateModel = createTemplateModel(verificationCode);
        mailUtils.sendMail(email, VERIFICATION_CODE.getSubject(), MAIL_TEMPLATE_FOR_CODE, templateModel);
    }

    @Transactional
    public void updateConfiguration(UserRequest.UpdateConfigurationDTO requestDTO, Long userId) {
        User user = userRepository.findById(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        checkNoDuplicateSshHosts(requestDTO.sshInfos());
        checkIsSshHostsEmpty(requestDTO.sshInfos());
        checkMonitoringSshHostSelected(requestDTO);

        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
        updateOrDeleteSshInfo(sshInfos, requestDTO.sshInfos());
        List<SshInfo> addedSshHosts = saveNewSshInfos(sshInfos, requestDTO.sshInfos(), user);

        SshInfo monitoringSshInfo = findMonitoringSshInfo(sshInfos, addedSshHosts, requestDTO.monitoringSshHost());
        user.updateConfiguration(requestDTO.nickName(), requestDTO.receivingAlarm(), monitoringSshInfo.getId());

        cacheUserSshInfo(userId, monitoringSshInfo);
    }

    @Transactional
    public void updateMonitoringSsh(Long userId, String monitoringSshHost) {
        User user = userRepository.findById(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        SshInfo monitoringSshInfo = findMonitoringSshInfo(userId, monitoringSshHost);
        user.updateMonitoringSshInfoId(monitoringSshInfo.getId());
    }

    public Path uploadSSHKey(MultipartFile file) {
        validateFileExist(file);
        createDirectoryIfNotExist(SSH_DIRECTORY);

        Path path = getFilePath(SSH_DIRECTORY, file);
        writeFile(file, path);

        return path;
    }

    public void updateApiKey(String value) {
        UserResponse.EnvUpdateDTO responseDTO = webClient.post()
                .uri(buildURI(updateEnvUri))
                .bodyValue(new UserRequest.EnvUpdateDTO(OPEN_API_KEY, value))
                .retrieve()
                .bodyToMono(UserResponse.EnvUpdateDTO.class)
                .block();

        if (isUpdateSuccess(responseDTO)) {
            throw new CustomException(ExceptionCode.UPDATE_KEY_FAIL);
        }
    }

    @Transactional
    public void resetPassword(UserRequest.ResetPasswordDTO requestDTO) {
        // 요청으로 들어온 코드를 가지고 레디스에서 해당 이메일을 꺼내옴
        String email = redisService.getValueInString(CODE_TO_EMAIL_KEY_PREFIX, requestDTO.code());
        if (email == null) {
            throw new CustomException(ExceptionCode.BAD_APPROACH);
        }

        redisService.removeValue(CODE_TO_EMAIL_KEY_PREFIX, requestDTO.code());
        updatePassword(email, requestDTO.newPassword());
    }

    @Transactional
    public void withdrawMember(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        alarmRepository.deleteByUserId(userId);
        projectRepository.deleteByUserId(userId);
        summaryRepository.deleteByUserId(userId);
        metricRepository.deleteByUserId(userId);
        sshInfoRepository.deleteByUserId(userId);

        redisService.removeValue(REDIS_KEY_ACCESS_TOKEN, userId.toString());
        redisService.removeValue(REDIS_KEY_REFRESH_TOKEN, userId.toString());

        userRepository.delete(user);
    }

    public UserResponse.VerifyEmailCodeDTO verifyCode(UserRequest.VerifyCodeDTO requestDTO, String codeType) {
        if (redisService.isNotStoredValue(addCodeTypePrefix(codeType), requestDTO.email(), requestDTO.code()))
            return new UserResponse.VerifyEmailCodeDTO(false);

        // 계정 복구를 위해 호출 했다면, 복구 로직에서 요청으로 들어온 코드를 가지고 이메일을 얻기 위해 저장해놓는다 (보안을 위해 요청으로 코드만 받기 위해)
        if (CODE_TYPE_RECOVERY.equals(codeType))
            redisService.storeValue(CODE_TO_EMAIL_KEY_PREFIX, requestDTO.code(), requestDTO.email(), 5 * 60 * 1000L);

        return new UserResponse.VerifyEmailCodeDTO(true);
    }

    @Transactional(readOnly = true)
    public UserResponse.CheckEmailExistDTO checkEmailExist(String email) {
        boolean isValid = userRepository.doesNotExistByEmail(email);
        return new UserResponse.CheckEmailExistDTO(isValid);
    }

    @Transactional
    public UserResponse.CheckNickNameDTO checkNickNameDuplicate(UserRequest.CheckNickDTO requestDTO) {
        boolean isDuplicate = userRepository.existsByNickname(requestDTO.nickName());
        return new UserResponse.CheckNickNameDTO(isDuplicate);
    }

    @Transactional(readOnly = true)
    public UserResponse.CheckAccountExistDTO checkLocalAccountExist(UserRequest.EmailDTO requestDTO) {
        boolean isValid = userRepository.findByEmail(requestDTO.email()).isPresent();
        return new UserResponse.CheckAccountExistDTO(isValid);
    }

    public UserResponse.VerifySshConnectDTO verifySshConnect(UserRequest.VerifySshConnectDTO requestDTO) {
        boolean isValid = sshService.checkConnectionValid(requestDTO.remoteHost(), requestDTO.remoteName(), requestDTO.remoteKeyPath());
        return new UserResponse.VerifySshConnectDTO(isValid);
    }

    public UserResponse.ValidateOpenAIKeyDTO validateOpenAIKey(String apiKey) {
        return webClient.post()
                .uri(buildURI(requestValidateKeyUri))
                .bodyValue(new UserRequest.RequestValidateKeyDTO(apiKey))
                .retrieve()
                .bodyToMono(UserResponse.ValidateOpenAIKeyDTO.class)
                .block();
    }

    public Long validateAccessTokenInRedis(String accessToken) {
        if (JWTProvider.isInvalidJwtFormat(accessToken)) {
            throw new CustomException(ExceptionCode.TOKEN_WRONG);
        }

        Long userId = JWTProvider.extractUserIdFromToken(accessToken);
        if (redisService.isNotStoredValue(REDIS_KEY_ACCESS_TOKEN, String.valueOf(userId), accessToken)) {
            throw new CustomException(ExceptionCode.ACCESS_TOKEN_WRONG);
        }

        return userId;
    }

    @Transactional(readOnly = true)
    public UserResponse.ValidateAccessTokenDTO findNickName(Long userId) {
        String nickName = userRepository.findNickName(userId).orElse(null);
        return new UserResponse.ValidateAccessTokenDTO(nickName);
    }

    @Transactional(readOnly = true)
    public UserResponse.FindCloudInfoDTO findCloudInfo(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
        UserResponse.CloudInfoDTO selectedCloud = findSelectedCloud(sshInfos, user.getMonitoringSshId());
        List<UserResponse.CloudInfoDTO> cloudInfos = sshInfos.stream()
                .map(this::convertToCloudInfoDTO)
                .toList();

        return new UserResponse.FindCloudInfoDTO(cloudInfos, selectedCloud);
    }

    @Transactional(readOnly = true)
    public UserResponse.FindConfigurationInfoDTO findConfigurationInfo(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
        List<UserResponse.SshInfoDTO> sshInfoDTOS = sshInfos.stream()
                .map(this::createSshInfoDTO)
                .toList();

        return new UserResponse.FindConfigurationInfoDTO(
                user.getNickName(),
                sshInfoDTOS,
                user.getMonitoringSshId(),
                user.isReceivingAlarm());
    }

    private boolean isPasswordMatched(String requestPassword, String userPassword) {
        return !passwordEncoder.matches(requestPassword, userPassword);
    }

    private Map<String, String> createToken(User user) {
        String accessToken = JWTProvider.createAccessToken(user);
        String refreshToken = JWTProvider.createRefreshToken(user);

        redisService.storeValue(REDIS_KEY_ACCESS_TOKEN, String.valueOf(user.getId()), accessToken, JWTProvider.ACCESS_EXP_MILLI);
        redisService.storeValue(REDIS_KEY_REFRESH_TOKEN, String.valueOf(user.getId()), refreshToken, JWTProvider.REFRESH_EXP_MILLI);
        redisService.storeValue(REDIS_KEY_SESSION_ID, user.getId().toString());

        Map<String, String> tokens = new HashMap<>();
        tokens.put(REDIS_KEY_ACCESS_TOKEN, accessToken);
        tokens.put(REDIS_KEY_REFRESH_TOKEN, refreshToken);

        return tokens;
    }

    private void validateJoinRequest(UserRequest.JoinDTO requestDTO) {
        validatePassword(requestDTO);
        checkIsSshHostsEmpty(requestDTO.sshInfos());
        checkNoDuplicateSshHosts(requestDTO.sshInfos());
        checkAlreadyJoin(requestDTO.email());
        checkDuplicateNickname(requestDTO.nickName());
    }

    private void validatePassword(UserRequest.JoinDTO requestDTO) {
        if (!requestDTO.password().equals(requestDTO.passwordConfirm())) {
            throw new CustomException(ExceptionCode.USER_PASSWORD_WRONG);
        }
    }

    private void checkAlreadyJoin(String email) {
        if (userRepository.existsByEmail(email))
            throw new CustomException(ExceptionCode.USER_EMAIL_EXIST);
    }

    private void checkDuplicateNickname(String nickName) {
        if (userRepository.existsByNickname(nickName))
            throw new CustomException(ExceptionCode.USER_NICKNAME_EXIST);
    }


    private User saveUser(UserRequest.JoinDTO joinRequestDTO) {
        User user = User.builder()
                .nickName(joinRequestDTO.nickName())
                .email(joinRequestDTO.email())
                .password(passwordEncoder.encode(joinRequestDTO.password()))
                .receivingAlarm(joinRequestDTO.receivingAlarm())
                .build();

        userRepository.save(user);
        return user;
    }

    private List<SshInfo> saveSshInfos(List<UserRequest.SshInfoDTO> requestSshInfos, User user) {
        List<SshInfo> sshInfos = new ArrayList<>();

        requestSshInfos.forEach(sshInfoDTO -> {
            SshInfo sshInfo = SshInfo.builder()
                    .user(user)
                    .remoteName(sshInfoDTO.remoteName())
                    .remoteHost(sshInfoDTO.remoteHost())
                    .remoteKeyPath(sshInfoDTO.remoteKeyPath())
                    .build();

            sshInfoRepository.save(sshInfo);
            sshInfos.add(sshInfo);
        });

        return sshInfos;
    }

    private void setMonitoringSshInfo(String monitoringSshHost, List<SshInfo> sshInfos, User user) {
        Optional<SshInfo> monitoringSshInfo = sshInfos.stream()
                .filter(sshInfo -> sshInfo.getRemoteHost().equals(monitoringSshHost))
                .findFirst();

        if (monitoringSshInfo.isEmpty()) {
            throw new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT);
        }

        user.updateMonitoringSshInfoId(monitoringSshInfo.get().getId());
    }

    private void checkAlreadySendCode(String email, String codeType) {
        if (redisService.isValueExist(addCodeTypePrefix(codeType), email)) {
            throw new CustomException(ExceptionCode.ALREADY_SEND_EMAIL);
        }
    }

    private void storeVerificationCode(String email, String codeType, String verificationCode) {
        redisService.storeValue(addCodeTypePrefix(codeType), email, verificationCode, VERIFICATION_CODE_EXPIRATION_MS);
    }

    private Map<String, Object> createTemplateModel(String verificationCode) {
        Map<String, Object> templateModel = new HashMap<>();
        templateModel.put(MODEL_KEY_CODE, verificationCode);
        return templateModel;
    }

    private void checkMonitoringSshHostSelected(UserRequest.UpdateConfigurationDTO requestDTO) {
        if (requestDTO.sshInfos().stream()
                .noneMatch(sshInfoDTO -> sshInfoDTO.remoteHost().equals(requestDTO.monitoringSshHost()))) {
            throw new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT);
        }
    }

    private void updateOrDeleteSshInfo(List<SshInfo> storedSshInfos, List<UserRequest.SshInfoDTO> newSshInfoRequests) {
        Map<String, UserRequest.SshInfoDTO> newSshInfoMap = createNewSshInfoMap(newSshInfoRequests);

        for (SshInfo sshInfo : storedSshInfos) {
            UserRequest.SshInfoDTO foundSshInfoDTO = newSshInfoMap.get(sshInfo.getRemoteHost());

            if (foundSshInfoDTO != null) {
                updateSshInfo(sshInfo, foundSshInfoDTO);
            } else {
                metricRepository.deleteBySShInfoId(sshInfo.getId());
                sshInfoRepository.delete(sshInfo);
            }
        }
    }

    private Map<String, UserRequest.SshInfoDTO> createNewSshInfoMap(List<UserRequest.SshInfoDTO> newSshInfoRequests) {
        return newSshInfoRequests.stream()
                .collect(Collectors.toMap(
                        UserRequest.SshInfoDTO::remoteHost,
                        Function.identity(),
                        (existing, replacement) -> existing // 중복 키 발생 시 기존 값 유지
                ));
    }

    private void updateSshInfo(SshInfo sshInfo, UserRequest.SshInfoDTO sshInfoDTO) {
        sshInfo.updateSshInfo(sshInfoDTO.remoteHost(),
                sshInfoDTO.remoteName(),
                sshInfoDTO.remoteKeyPath(),
                true);
    }

    private List<SshInfo> saveNewSshInfos(List<SshInfo> existingSshInfos, List<UserRequest.SshInfoDTO> newSshInfoRequests, User user) {
        List<SshInfo> addedSshInfos = new ArrayList<>();

        Set<String> existingRemoteHosts = existingSshInfos.stream()
                .map(SshInfo::getRemoteHost)
                .collect(Collectors.toSet());

        for (UserRequest.SshInfoDTO sshInfoDTO : newSshInfoRequests) {
            String remoteHost = sshInfoDTO.remoteHost();

            if (!existingRemoteHosts.contains(remoteHost)) {
                SshInfo newSshInfo = SshInfo.builder()
                        .user(user)
                        .remoteHost(remoteHost)
                        .remoteName(sshInfoDTO.remoteName())
                        .remoteKeyPath(sshInfoDTO.remoteKeyPath())
                        .build();

                sshInfoRepository.save(newSshInfo);
                addedSshInfos.add(newSshInfo);
                existingRemoteHosts.add(remoteHost);
            }
        }
        return addedSshInfos;
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
        redisService.storeValue(REDIS_SSH_KEY, userId.toString(), combinedInfo, REDIS_SSH_KEY_EXP);
    }

    private SshInfo findMonitoringSshInfo(Long userId, String monitoringSshHost) {
        return sshInfoRepository.findByUserId(userId).stream()
                .filter(sshInfo -> sshInfo.getRemoteHost().equals(monitoringSshHost))
                .findFirst()
                .orElseThrow(() -> new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT));
    }

    private boolean isUpdateSuccess(UserResponse.EnvUpdateDTO responseDTO) {
        return responseDTO == null || !responseDTO.success();
    }

    private void updatePassword(String email, String newPassword) {
        User user = userRepository.findByEmail(email).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_EMAIL_NOT_FOUND)
        );

        user.updatePassword(passwordEncoder.encode(newPassword));
    }

    private UserResponse.CloudInfoDTO findSelectedCloud(List<SshInfo> sshInfos, Long monitoringSshId) {
        return sshInfos.stream()
                .filter(sshInfo -> sshInfo.getId().equals(monitoringSshId))
                .map(this::convertToCloudInfoDTO)
                .findFirst()
                .orElseThrow(() -> new CustomException(ExceptionCode.MONITORING_SSH_NOT_FOUND));
    }

    private UserResponse.SshInfoDTO createSshInfoDTO(SshInfo sshInfo) {
        return new UserResponse.SshInfoDTO(
                sshInfo.getId(),
                sshInfo.getRemoteName(),
                sshInfo.getRemoteHost(),
                sshInfo.getRemoteKeyPath(),
                sshInfo.isWorking());
    }

    private String addCodeTypePrefix(String codeType) {
        return UserService.PREFIX_CODE + codeType;
    }

    private UserResponse.CloudInfoDTO convertToCloudInfoDTO(SshInfo sshInfo) {
        return new UserResponse.CloudInfoDTO(sshInfo.getRemoteName(), sshInfo.getRemoteHost());
    }

    private void checkNoDuplicateSshHosts(List<UserRequest.SshInfoDTO> sshInfos) {
        Set<String> remoteHostSet = sshInfos.stream()
                .map(UserRequest.SshInfoDTO::remoteHost)
                .collect(Collectors.toSet());

        if (remoteHostSet.size() < sshInfos.size()) {
            throw new CustomException(ExceptionCode.DUPLICATE_SSH_HOST);
        }
    }

    private void checkIsSshHostsEmpty(List<UserRequest.SshInfoDTO> sshInfoDTOS) {
        if (sshInfoDTOS.isEmpty()) {
            throw new CustomException(ExceptionCode.SSH_INFO_EMPTY);
        }
    }
}