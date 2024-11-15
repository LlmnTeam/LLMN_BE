package com.example.llmn.service;

import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.controller.DTO.UserRequest;
import com.example.llmn.controller.DTO.UserResponse;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.core.security.JWTProvider;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.domain.SummaryType;
import com.example.llmn.domain.User;
import com.example.llmn.repository.*;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseCookie;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.example.llmn.core.utils.FileUtils.getFilePath;
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
    private final MetricService metricService;
    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;
    private final WebClient webClient;

    @Value("${spring.mail.username}")
    private String SERVICE_MAIL_ACCOUNT;

    @Value("${reload.uri}")
    private String REQUEST_RELOAD_KEY_URI;

    @Value("${validate_key.uri}")
    private String REQUEST_VALIDATE_KEY_URI;

    @Value("${update_env.uri}")
    private String UPDATE_ENV_URI;

    private static final String REDIS_KEY_EMAIL_CODE = "code:";
    private static final String MAIL_TEMPLATE_FOR_CODE = "verification_code_email.html";
    private static final String UTF_EIGHT_ENCODING = "UTF-8";
    private static final String UPLOAD_DIR = "ssh";
    private static final String REDIS_KEY_SESSION_ID = "sessionId";
    private static final String REDIS_KEY_REFRESH_TOKEN = "refreshToken";
    private static final String REDIS_KEY_ACCESS_TOKEN = "accessToken";
    private static final String REDIS_SSH_KEY = "SSH";
    private static final String COOKIE_KEY_REFRESH_TOKEN = "refreshToken";
    private static final String SORT_BY_DATE = "createdDate";
    private static final String MODEL_KEY_CODE = "code";
    private static final String DELIMITER = "-";
    private static final Long REDIS_SSH_KEY_EXP = 60L * 60 * 24 * 180; // 180일
    private static final String NOT_AVAILABLE = "N/A";
    private static final String ENV_FILE_RELATIVE_PATH = "FastAPI/app/.env";
    private static final String OPEN_API_KEY = "OPENAI_API_KEY";
    private static final String CODE_TO_EMAIL_KEY_PREFIX = "codeToEmail";
    private static final String CODE_TYPE_RECOVERY = "recovery";

    @Transactional
    public Map<String, String> login(UserRequest.LoginDTO requestDTO, HttpServletRequest request) {
        User user = userRepository.findByEmail(requestDTO.email()).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_ACCOUNT_WRONG)
        );

        // 비밀번호가 일치하지 않음
        if(!passwordEncoder.matches(requestDTO.password(), user.getPassword())){
            throw new CustomException(ExceptionCode.USER_ACCOUNT_WRONG);
        }

        return createToken(user);
    }

    @Transactional
    public void join(UserRequest.JoinDTO requestDTO){
        validateJoinRequest(requestDTO);

        // 유저 엔티티 저장
        User user = saveUser(requestDTO);

        // SSH 엔티티 저장 및 모니터링 SSH 정보 설정
        List<SshInfo> sshInfos = saveSshInfos(requestDTO.sshInfos(), user);
        setMonitoringSshInfo(requestDTO.monitoringSshHost(), sshInfos, user);

        // OpenAI API 키 저장
        updateFastAPIEnvFile(OPEN_API_KEY, requestDTO.openAiKey());
    }

    @Transactional(readOnly = true)
    public UserResponse.CheckEmailExistDTO checkEmailExist(String email){
        boolean isValid = !userRepository.existsByEmailWithRemoved(email);
        return new UserResponse.CheckEmailExistDTO(isValid);
    }

    @Transactional
    public UserResponse.CheckNickNameDTO checkNickName(UserRequest.CheckNickDTO requestDTO){
        boolean isDuplicate = userRepository.existsByNickname(requestDTO.nickName());
        return new UserResponse.CheckNickNameDTO(isDuplicate);
    }

    @Async
    public void sendCodeWithValidation(String email, String codeType, boolean isValid) {
        // TTL 체크
        if(redisService.isDateExist(REDIS_KEY_EMAIL_CODE + codeType, email)){
            throw new CustomException(ExceptionCode.ALREADY_SEND_EMAIL);
        }

        // 유효한 경우에만 메일 전송
        if(isValid){
            sendCodeByEmail(email, codeType);
        }
    }

    public UserResponse.VerifyEmailCodeDTO verifyCode(UserRequest.VerifyCodeDTO requestDTO, String codeType){
        // 레디스를 통해 해당 코드가 유효한지 확인
        if(!redisService.validateData(REDIS_KEY_EMAIL_CODE + codeType, requestDTO.email(), requestDTO.code()))
            return new UserResponse.VerifyEmailCodeDTO(false);

        if(CODE_TYPE_RECOVERY.equals(codeType))
            redisService.storeValue(CODE_TO_EMAIL_KEY_PREFIX, requestDTO.code(), requestDTO.email(), 5 * 60 * 1000L);

        return new UserResponse.VerifyEmailCodeDTO(true);
    }

    public UserResponse.VerifySshConnectDTO verifySshConnect(UserRequest.VerifySshConnectDTO requestDTO){
        boolean isValid = sshService.checkConnectionValid(requestDTO.remoteHost(), requestDTO.remoteName(), requestDTO.remoteKeyPath());
        return new UserResponse.VerifySshConnectDTO(isValid);
    }

    public Path uploadSSHKey(MultipartFile file) {
        // 요청으로 들어온 파일이 없음
        if (file.isEmpty()) {
            throw new CustomException(ExceptionCode.NO_FILE_TO_UPLOAD);
        }

        // 로그 파일 디렉토리가 없으면 생성
        createDirIfNotExist();

        // 파일 업로드 경로 구하기
        Path path = getFilePath(UPLOAD_DIR, file);

        // 파일 업로드
        try {
            Files.write(path, file.getBytes());
        } catch (IOException e) {
            log.error("업로드 파일 저장 실패");
            throw new CustomException(ExceptionCode.SAVE_FILE_FAIL);
        }

        return path;
    }

    public UserResponse.ValidateOpenAIKeyDTO validateOpenAIKey(String apiKey){
        return webClient.post()
                .uri(buildURI(REQUEST_VALIDATE_KEY_URI))
                .bodyValue(new UserRequest.RequestValidateKeyDTO(apiKey))
                .retrieve()
                .bodyToMono(UserResponse.ValidateOpenAIKeyDTO.class)
                .block();
    }

    @Transactional
    public void resetPassword(UserRequest.ResetPasswordDTO requestDTO){
        // 전송한 코드로 세션에서 해당 이메일을 꺼내옴 (비밀번호 재설정 시 코드 전송을 거침)
        String email = redisService.getDataInStr(CODE_TO_EMAIL_KEY_PREFIX, requestDTO.code());
        if(email == null){
            throw new CustomException(ExceptionCode.BAD_APPROACH);
        }

        // CODE_TO_EMAIL 키 삭제
        redisService.removeData(CODE_TO_EMAIL_KEY_PREFIX, requestDTO.code());

        // 새로운 비밀번호로 업데이트
        User user = userRepository.findByEmail(email).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_EMAIL_NOT_FOUND)
        );
        user.updatePassword(passwordEncoder.encode(requestDTO.newPassword()));
    }

    @Transactional
    public UserResponse.FindDashboardDTO findDashboard(Long userId) {
        Long sshInfoId = userRepository.findMonitoringSshId(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // 1. 원격 클라우드 호스트 정보
        String remoteHost = sshInfoRepository.findHostById(sshInfoId).orElseThrow(
                () -> new CustomException(ExceptionCode.SSH_NOT_FOUND)
        );

        // 2. 현재 지표
        MetricResponse.FindCurrentMetricDTO currentMetric = metricService.findCurrentMetric(sshInfoId);

        // 3. 과거 지표
        MetricResponse.FindMetricHistoryDTO metricHistory = metricService.findMetricHistory(24, sshInfoId);

        // 4. 시간별 요약
        String hourlySummary = getLatestHourlySummary()
                .orElse("요약된 내용이 존재하지 않습니다.");

        return new UserResponse.FindDashboardDTO(
                remoteHost,
                formatCpuUsage(currentMetric),
                formatMemoryUsage(currentMetric),
                formatNetworkReceived(currentMetric),
                formatNetworkSent(currentMetric),
                hourlySummary,
                metricHistory.cpuMetrics(),
                metricHistory.memoryMetrics(),
                metricHistory.networkInMetrics(),
                metricHistory.networkOutMetrics());
    }

    @Transactional(readOnly = true)
    public UserResponse.FindCloudInfoDTO findCloudInfo(Long userId){
        User user = userRepository.findById(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);

        List<UserResponse.CloudInfoDTO> cloudInfoDTOS = sshInfos.stream()
                .map(sshInfo -> new UserResponse.CloudInfoDTO(sshInfo.getRemoteName(), sshInfo.getRemoteHost()))
                .toList();

        UserResponse.CloudInfoDTO selectedCloudDTO = sshInfos.stream()
                .filter(sshInfo -> sshInfo.getId().equals(user.getMonitoringSshId()))
                .map(sshInfo -> new UserResponse.CloudInfoDTO(sshInfo.getRemoteName(), sshInfo.getRemoteHost()))
                .findFirst()
                .get();

        return new UserResponse.FindCloudInfoDTO(cloudInfoDTOS, selectedCloudDTO);
    }

    @Transactional
    public void updateMonitoringSsh(Long userId ,String monitoringSshHost){
        User user = userRepository.findById(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);

        SshInfo foundSshInfo = sshInfos.stream()
                .filter(sshInfo -> sshInfo.getRemoteHost().equals(monitoringSshHost))
                .findFirst()
                .orElseThrow(
                        () -> new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT)
                );

        user.updateMonitoringSshInfoId(foundSshInfo.getId());
    }

    @Transactional(readOnly = true)
    public UserResponse.FindConfigurationInfoDTO findConfigurationInfo(Long userId){
        User user = userRepository.findById(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
        List<UserResponse.SshInfoDTO> sshInfoDTOS = sshInfos.stream()
                .map(sshInfo -> new UserResponse.SshInfoDTO(
                        sshInfo.getId(),
                        sshInfo.getRemoteName(),
                        sshInfo.getRemoteHost(),
                        sshInfo.getRemoteKeyPath(),
                        sshInfo.isWorking()))
                .toList();

        return new UserResponse.FindConfigurationInfoDTO(
                user.getNickName(),
                sshInfoDTOS,
                user.getMonitoringSshId(),
                user.isReceivingAlarm());
    }

    @Transactional
    public void updateConfiguration(UserRequest.UpdateConfigurationDTO requestDTO, Long userId){
        User user = userRepository.findById(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // SSH Host 중복 방지
        if(hasDuplicateRemoteHost(requestDTO.sshInfos())){
            throw new CustomException(ExceptionCode.DUPLICATE_SSH_HOST);
        }

        // SSH 정보가 비어 있으면 안됨
        List<UserRequest.SshInfoDTO> requestSshInfoDTOS = requestDTO.sshInfos();
        if(requestSshInfoDTOS.isEmpty()){
            throw new CustomException(ExceptionCode.SSH_INFO_EMPTY);
        }

        // 1. SshInfo 엔티티들 업데이트
        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);

        updateOrDeleteSshInfo(sshInfos, requestSshInfoDTOS);
        List<SshInfo> addedSshHosts = addNewSshInfos(sshInfos, requestSshInfoDTOS, user);

        // 2. 모니터링 할 클라우드 인스턴스의 SshInfo 객체 찾기
        String monitoringSshHost = requestSshInfoDTOS.stream()
                .filter(sshInfoDTO -> sshInfoDTO.remoteHost().equals(requestDTO.monitoringSshHost()))
                .findFirst()
                .orElseThrow(() -> new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT))
                .remoteHost();

        SshInfo monitoringSshInfo = findMonitoringSshInfo(sshInfos, addedSshHosts, monitoringSshHost);

        // 3. 유저 정보 업데이트
        user.updateConfiguration(requestDTO.nickName(), requestDTO.receivingAlarm(), monitoringSshInfo.getId());

        // 4. 캐시 업데이트
        String combinedInfo = String.join(DELIMITER, monitoringSshInfo.getRemoteHost(), monitoringSshInfo.getRemoteName(), monitoringSshInfo.getRemoteKeyPath());
        redisService.storeValue(REDIS_SSH_KEY, userId.toString(), combinedInfo, REDIS_SSH_KEY_EXP);
    }

    public void updateApiKey(String apiKey){
        updateFastAPIEnvFile(OPEN_API_KEY, apiKey);
    }

    @Transactional(readOnly = true)
    public UserResponse.ValidateAccessTokenDTO validateAccessToken(@CookieValue String accessToken){
        // 잘못된 토큰 형식인지 체크
        if(!JWTProvider.validateToken(accessToken)) {
            throw new CustomException(ExceptionCode.TOKEN_WRONG);
        }

        Long userIdFromToken = JWTProvider.getUserIdFromToken(accessToken);
        if(!redisService.validateValue(REDIS_KEY_ACCESS_TOKEN, String.valueOf(userIdFromToken), accessToken)){
            throw new CustomException(ExceptionCode.ACCESS_TOKEN_WRONG);
        }

        String nickName = userRepository.findNickName(userIdFromToken).orElse(null);

        return new UserResponse.ValidateAccessTokenDTO(nickName);
    }

    private Map<String, String> createToken(User user){
        String accessToken = JWTProvider.createAccessToken(user);
        String refreshToken = JWTProvider.createRefreshToken(user);

        // Access Token 갱신
        redisService.storeValue(REDIS_KEY_ACCESS_TOKEN, String.valueOf(user.getId()), accessToken, JWTProvider.ACCESS_EXP_MILLI);

        // Refresh Token 갱신
        redisService.storeValue(REDIS_KEY_REFRESH_TOKEN, String.valueOf(user.getId()), refreshToken, JWTProvider.REFRESH_EXP_MILLI);

        // 로그인 ID를 세션으로 저장
        redisService.storeValue(REDIS_KEY_SESSION_ID, user.getId().toString());

        // Map으로 토큰들을 담아 반환
        Map<String, String> tokens = new HashMap<>();
        tokens.put(REDIS_KEY_ACCESS_TOKEN, accessToken);
        tokens.put(REDIS_KEY_REFRESH_TOKEN, refreshToken);

        return tokens;
    }

    public String createRefreshTokenCookie(String refreshToken) {
        return ResponseCookie.from(COOKIE_KEY_REFRESH_TOKEN, refreshToken)
                .httpOnly(true)
                .secure(false)
                .path("/")
                .sameSite("Lax")
                .maxAge(JWTProvider.REFRESH_EXP_SEC)
                .build().toString();
    }

    @Transactional(readOnly = true)
    public UserResponse.CheckAccountExistDTO checkLocalAccountExist(UserRequest.EmailDTO requestDTO){
        Optional<User> userOP = userRepository.findByEmail(requestDTO.email());
        boolean isValid = userOP.isPresent();

        return new UserResponse.CheckAccountExistDTO(isValid);
    }

    @Transactional
    public void withdrawMember(Long userId){
        User user = userRepository.findById(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // 알람 삭제
        alarmRepository.deleteByUserId(userId);

        // 프로젝트 삭제
        projectRepository.deleteByUserId(userId);

        // 요약 삭제
        summaryRepository.deleteByUserId(userId);

        // 메트릭 삭제
        metricRepository.deleteByUserId(userId);

        // SSH 정보 삭제
        sshInfoRepository.deleteByUserId(userId);

        // 세션에 저장된 토큰 삭제
        redisService.removeData(REDIS_KEY_ACCESS_TOKEN, userId.toString());
        redisService.removeData(REDIS_KEY_REFRESH_TOKEN, userId.toString());

        userRepository.delete(user);
    }

    private void checkDuplicateNickname(String nickName) {
        if(userRepository.existsByNickname(nickName))
            throw new CustomException(ExceptionCode.USER_NICKNAME_EXIST);
    }

    private void checkAlreadyJoin(String email) {
        // 로컬 회원 가입을 통해 이미 가입함
        if(userRepository.existsByEmail(email))
            throw new CustomException(ExceptionCode.USER_EMAIL_EXIST);
    }

    private String generateVerificationCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();

        return IntStream.range(0, 8) // 8자리
                .map(i -> random.nextInt(chars.length()))
                .mapToObj(chars::charAt)
                .map(Object::toString)
                .collect(Collectors.joining());
    }

    @Async
    public void sendCodeByEmail(String email, String codeType) {
        // 인증 코드 전송 및 레디스에 저장
        String verificationCode = generateVerificationCode();

        // 메일 전송 템플릿 보낼 데이터는 map에 담음
        Map<String, Object> model = new HashMap<>();
        model.put(MODEL_KEY_CODE, verificationCode);

        sendMail(email, VERIFICATION_CODE.getSubject(), MAIL_TEMPLATE_FOR_CODE, model);

        redisService.storeValue(REDIS_KEY_EMAIL_CODE + codeType, email, verificationCode, 175 * 1000L); // 3분 동안 유효
    }

    @Async
    public void sendMail(String toEmail, String subject, String templateName, Map<String, Object> templateModel) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, UTF_EIGHT_ENCODING);

            // 템플릿 설정
            Context context = new Context();
            templateModel.forEach(context::setVariable);
            String htmlContent = templateEngine.process(templateName, context);
            helper.setText(htmlContent, true);

            helper.setFrom(SERVICE_MAIL_ACCOUNT);
            helper.setTo(toEmail);
            helper.setSubject(subject);

            mailSender.send(message);
        } catch (MessagingException e){
            log.info(toEmail + "로의 메일 전송에 실패했습니다");
        }
    }

    private void validateJoinRequest(UserRequest.JoinDTO requestDTO) {
        if (!requestDTO.password().equals(requestDTO.passwordConfirm()))
            throw new CustomException(ExceptionCode.USER_PASSWORD_WRONG);

        checkAlreadyJoin(requestDTO.email());
        checkDuplicateNickname(requestDTO.nickName());

        // SshInfo가 비어 있음
        if(requestDTO.sshInfos().isEmpty()){
            throw new CustomException(ExceptionCode.SSH_INFO_EMPTY);
        }

        // Ssh Host가 중복된 게 있는지 체크
        if(hasDuplicateRemoteHost(requestDTO.sshInfos())){
            throw new CustomException(ExceptionCode.DUPLICATE_SSH_HOST);
        }
    }

    private Optional<String> getLatestHourlySummary(){
        Pageable pageable = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, SORT_BY_DATE));
        Page<String> page = summaryRepository.findContentByType(SummaryType.HOURLY, pageable);
        return page.hasContent() ? Optional.of(page.getContent().get(0)) : Optional.empty();
    }

    private List<SshInfo> addNewSshInfos(List<SshInfo> sshInfos, List<UserRequest.SshInfoDTO> requestSshInfoDTOS, User user) {
        List<SshInfo> addedSshHosts = new ArrayList<>();

        // 새로운 SshInfo가 요청으로 들어왔으면 저장
        for (UserRequest.SshInfoDTO sshInfoDTO : requestSshInfoDTOS) {
            boolean exists = sshInfos.stream()
                    .anyMatch(sshInfo -> sshInfo.getRemoteHost().equals(sshInfoDTO.remoteHost()));

            if (!exists) {
                SshInfo newSshInfo = SshInfo.builder()
                        .user(user)
                        .remoteHost(sshInfoDTO.remoteHost())
                        .remoteName(sshInfoDTO.remoteName())
                        .remoteKeyPath(sshInfoDTO.remoteKeyPath())
                        .build();

                sshInfoRepository.save(newSshInfo);
                addedSshHosts.add(newSshInfo);
            }
        }

        return addedSshHosts;
    }

    private void updateOrDeleteSshInfo(List<SshInfo> sshInfos, List<UserRequest.SshInfoDTO> requestSshInfoDTOS) {
        // 기존의 SshInfo와 요청으로 들어온 값을 비교 => 변경된 부분이 있는지 비교하고 업데이트하거나, 새로운 엔티티는 저장
        for (SshInfo sshInfo : sshInfos) {
            Optional<UserRequest.SshInfoDTO> matchingDTO = requestSshInfoDTOS.stream()
                    .filter(sshInfoDTO -> sshInfoDTO.remoteHost().equals(sshInfo.getRemoteHost()))
                    .findFirst();

            if (matchingDTO.isPresent()) {
                updateSshInfo(sshInfo, matchingDTO.get());
            } else {
                metricRepository.deleteBySShInfoId(sshInfo.getId());
                sshInfoRepository.delete(sshInfo);
            }
        }
    }

    private void updateSshInfo(SshInfo sshInfo, UserRequest.SshInfoDTO sshInfoDTO) {
        sshInfo.updateSshInfo(sshInfoDTO.remoteHost(),
                sshInfoDTO.remoteName(),
                sshInfoDTO.remoteKeyPath(),
                true);
    }

    // monitoringSshHost를 가진 SshInfo 객체 찾기
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

    private boolean hasDuplicateRemoteHost(List<UserRequest.SshInfoDTO> sshInfos) {
        Set<String> remoteHostSet = new HashSet<>();

        for (UserRequest.SshInfoDTO sshInfoDTO : sshInfos) {
            if (!remoteHostSet.add(sshInfoDTO.remoteHost())) {
                return true;
            }
        }
        return false;
    }

    private void createDirIfNotExist() {
        Path uploadPath = Paths.get(UPLOAD_DIR);

        try {
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
        } catch (IOException e){
            throw new CustomException(ExceptionCode.CREATE_DIR_FAIL);
        }
    }

    private void updateFastAPIEnvFile(String key, String value) {
        try {
            UserResponse.EnvUpdateDTO responseDTO = webClient.post()
                    .uri(buildURI(UPDATE_ENV_URI))
                    .bodyValue(new UserRequest.EnvUpdateDTO(key, value))
                    .retrieve()
                    .bodyToMono(UserResponse.EnvUpdateDTO.class)
                    .block();

            if (responseDTO == null || !responseDTO.success()) {
                throw new CustomException(ExceptionCode.CONVERT_TO_FILE_FAIL);
            }

        } catch (Exception e){
            log.info("API 키 업데이트 실패");
            throw new CustomException(ExceptionCode.CONVERT_TO_FILE_FAIL);
        }
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

    private void setMonitoringSshInfo(String monitoringSshHost, List<SshInfo> sshInfos, User user) {
        Optional<SshInfo> monitoringSshInfo = sshInfos.stream()
                .filter(sshInfo -> sshInfo.getRemoteHost().equals(monitoringSshHost))
                .findFirst();

        if (monitoringSshInfo.isEmpty()) {
            throw new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT);
        }

        user.updateMonitoringSshInfoId(monitoringSshInfo.get().getId());
    }

    private String formatCpuUsage(MetricResponse.FindCurrentMetricDTO currentMetric) {
        return Optional.ofNullable(currentMetric)
                .map(metric -> String.format("%.2f%%", metric.cpuUsage()))
                .orElse(NOT_AVAILABLE);
    }

    private String formatMemoryUsage(MetricResponse.FindCurrentMetricDTO currentMetric) {
        return Optional.ofNullable(currentMetric)
                .filter(metric -> metric.totalMemory() > 0)
                .map(metric -> String.format("%.2f%%", (metric.usedMemory() / metric.totalMemory()) * 100))
                .orElse(NOT_AVAILABLE);
    }

    private String formatNetworkReceived(MetricResponse.FindCurrentMetricDTO currentMetric) {
        return Optional.ofNullable(currentMetric)
                .map(metric -> String.format("%.2f MB", metric.networkReceived()))
                .orElse(NOT_AVAILABLE);
    }

    private String formatNetworkSent(MetricResponse.FindCurrentMetricDTO currentMetric) {
        return Optional.ofNullable(currentMetric)
                .map(metric -> String.format("%.2f MB", metric.networkSent()))
                .orElse(NOT_AVAILABLE);
    }
}