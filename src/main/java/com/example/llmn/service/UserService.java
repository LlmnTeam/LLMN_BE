package com.example.llmn.service;

import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.controller.DTO.UserRequest;
import com.example.llmn.controller.DTO.UserResponse;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.core.security.JWTProvider;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.domain.Summary;
import com.example.llmn.domain.SummaryType;
import com.example.llmn.domain.User;
import com.example.llmn.repository.SshInfoRepository;
import com.example.llmn.repository.SummaryRepository;
import com.example.llmn.repository.UserRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.multipart.MultipartFile;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.example.llmn.core.utils.MailTemplate.VERIFICATION_CODE;

@Service
@RequiredArgsConstructor
public class UserService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final SshInfoRepository sshInfoRepository;
    private final RedisService redisService;
    private final SummaryRepository summaryRepository;
    private final MetricService metricService;
    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;

    @Value("${spring.mail.username}")
    private String SERVICE_MAIL_ACCOUNT;
    private static final String EMAIL_CODE_KEY_PREFIX = "code:";
    private static final String MAIL_TEMPLATE_FOR_CODE = "verification_code_email.html";
    private static final String UTF_EIGHT_ENCODING = "UTF-8";
    private static final String UPLOAD_DIR = "ssh";

    @Transactional
    public Map<String, String> login(UserRequest.@Valid LoginDTO requestDTO, HttpServletRequest request) throws MessagingException {
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
        if (!requestDTO.password().equals(requestDTO.passwordConfirm()))
            throw new CustomException(ExceptionCode.USER_PASSWORD_WRONG);

        // 이미 가입된 계정인지 체크
        checkAlreadyJoin(requestDTO.email());

        // 중복된 닉네임 다시 체크 (프론트에서 체크하고 이중 체크)
        checkDuplicateNickname(requestDTO.nickName());

        SshInfo sshInfo = SshInfo.builder()
                .remoteName(requestDTO.remoteName())
                .remoteHost(requestDTO.remoteHost())
                .remoteKeyPath(requestDTO.remoteKeyPath())
                .build();

        sshInfoRepository.save(sshInfo);

        User user = User.builder()
                .nickName(requestDTO.nickName())
                .email(requestDTO.email())
                .password(passwordEncoder.encode(requestDTO.password()))
                .sshInfo(sshInfo)
                .receivingAlarm(requestDTO.receivingAlarm())
                .build();

        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public UserResponse.CheckEmailExistDTO checkEmailExist(String email){
        boolean isValid = !userRepository.existsByEmailWithRemoved(email);
        return new UserResponse.CheckEmailExistDTO(isValid);
    }

    @Async
    public void sendCodeWithValidation(String email, String codeType, boolean isValid) throws MessagingException {
        // TTL 체크
        if(redisService.isDateExist(EMAIL_CODE_KEY_PREFIX + codeType, email)){
            throw new CustomException(ExceptionCode.ALREADY_SEND_EMAIL);
        }

        // 유효한 경우에만 메일 전송
        if(isValid){
            sendCodeByEmail(email, codeType);
        }
    }

    public Path uploadSSHKey(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("파일이 없습니다.");
        }

        // 디렉토리가 없으면 생성
        Path uploadPath = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        String fileName = file.getOriginalFilename();
        Path path = Paths.get(UPLOAD_DIR + File.separator + fileName);

        // 파일이 이미 존재하는지 확인
        if (Files.exists(path)) {
            throw new FileAlreadyExistsException("파일이 이미 존재합니다.");
        }

        Files.write(path, file.getBytes());

        return path;
    }

    @Transactional(readOnly = true)
    public UserResponse.FindDashboardDTO findDashboard(Long userId) throws Exception {
        SshInfo sshInfo = userRepository.findSshInfoById(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // 현재 지표
        MetricResponse.FindCurrentMetricDTO currentMetric = metricService.findCurrentMetric(userId);
        String cpuUsage = String.format("%.2f%%", currentMetric.cpuUsage());
        String memoryUsage = currentMetric.totalMemory() > 0
                ? String.format("%.2f%%", (currentMetric.usedMemory() / currentMetric.totalMemory()) * 100)
                : "N/A";
        String networkReceived = String.format("%.2f MB", currentMetric.networkReceived());
        String networkSent = String.format("%.2f MB", currentMetric.networkSent());

        // 과거 지표
        MetricResponse.FindMetricHistoryDTO metricHistory = metricService.findMetricHistory(24, userId);

        // 시간별 요약
        String summary = getLatestHourlySummary()
                .orElse("요약된 내용이 존재하지 않습니다.");

        return new UserResponse.FindDashboardDTO(
                sshInfo.getRemoteHost(),
                cpuUsage,
                memoryUsage,
                networkReceived,
                networkSent,
                summary,
                metricHistory.cpuMetrics(),
                metricHistory.memoryMetrics(),
                metricHistory.networkInMetrics(),
                metricHistory.networkOutMetrics());
    }

    @Transactional(readOnly = true)
    public UserResponse.FindConfigurationInfoDTO findConfigurationInfo(Long userId){
        // SshInfo 패치조인
        User user = userRepository.findByIdWithSshInfo(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        SshInfo sshInfo = user.getSshInfo();

        return new UserResponse.FindConfigurationInfoDTO(
                user.getNickName(),
                sshInfo.getRemoteName(),
                sshInfo.getRemoteHost(),
                sshInfo.getRemoteKeyPath(),
                user.isReceivingAlarm());
    }

    @Transactional
    public void updateConfiguration(UserRequest.UpdateConfigurationDTO requestDTO, Long userId){
        // SshInfo 패치조인
        User user = userRepository.findByIdWithSshInfo(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // SSH 정보 업데이트
        SshInfo sshInfo = user.getSshInfo();
        sshInfo.updateSshInfo(requestDTO.remoteHost(), requestDTO.remoteName(), requestDTO.remoteKeyPath());

        // 유저 정보 업데이트
        user.updateConfiguration(requestDTO.nickName(), requestDTO.receivingAlarm());
    }

    private Map<String, String> createToken(User user){
        String accessToken = JWTProvider.createAccessToken(user);
        String refreshToken = JWTProvider.createRefreshToken(user);

        // Access Token 갱신
        redisService.storeValue("accessToken", String.valueOf(user.getId()), accessToken, JWTProvider.ACCESS_EXP_MILLI);

        // Refresh Token 갱신
        redisService.storeValue("refreshToken", String.valueOf(user.getId()), refreshToken, JWTProvider.REFRESH_EXP_MILLI);

        // 로그인 ID를 세션으로 저장
        redisService.storeValue("sessionId", user.getId().toString());

        // Map으로 토큰들을 담아 반환
        Map<String, String> tokens = new HashMap<>();
        tokens.put("accessToken", accessToken);
        tokens.put("refreshToken", refreshToken);

        return tokens;
    }

    public String createRefreshTokenCookie(String refreshToken) {
        return ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(false)
                .path("/")
                .sameSite("Lax")
                .maxAge(JWTProvider.REFRESH_EXP_SEC)
                .build().toString();
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
    public void sendCodeByEmail(String email, String codeType) throws MessagingException {
        // 인증 코드 전송 및 레디스에 저장
        String verificationCode = generateVerificationCode();

        // 메일 전송 템플릿 보낼 데이터는 map에 담음
        Map<String, Object> model = new HashMap<>();
        model.put("code", verificationCode);

        sendMail(email, VERIFICATION_CODE.getSubject(), MAIL_TEMPLATE_FOR_CODE, model);

        redisService.storeValue(EMAIL_CODE_KEY_PREFIX + codeType, email, verificationCode, 175 * 1000L); // 3분 동안 유효
    }

    @Async
    public void sendMail(String toEmail, String subject, String templateName, Map<String, Object> templateModel) throws MessagingException {
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
    }

    private Optional<String> getLatestHourlySummary(){
        Pageable pageable = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdDate"));
        Page<String> page = summaryRepository.findContentByType(SummaryType.HOURLY, pageable);
        return page.hasContent() ? Optional.of(page.getContent().get(0)) : Optional.empty();
    }
}