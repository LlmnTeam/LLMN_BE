package com.example.llmn.service;

import com.example.llmn.controller.DTO.UserRequest;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.core.security.JWTProvider;
import com.example.llmn.domain.User;
import com.example.llmn.domain.UserRole;
import com.example.llmn.repository.UserRepository;
import jakarta.mail.MessagingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final RedisService redisService;

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

        User user = User.builder()
                .nickName(requestDTO.nickName())
                .email(requestDTO.email())
                .password(passwordEncoder.encode(requestDTO.password()))
                .role(UserRole.USER)
                .isLocal(requestDTO.isLocal())
                .remoteName(requestDTO.remoteName())
                .remoteHost(requestDTO.remoteHost())
                .remotePort(requestDTO.remotePort())
                .remoteKeyPath(requestDTO.remoteKeyPath())
                .build();

        userRepository.save(user);
    }

    private Map<String, String> createToken(User user){
        String accessToken = JWTProvider.createAccessToken(user);
        String refreshToken = JWTProvider.createRefreshToken(user);

        // Access Token 갱신
        redisService.storeValue("accessToken", String.valueOf(user.getId()), accessToken, JWTProvider.ACCESS_EXP_MILLI);

        // Refresh Token 갱신
        redisService.storeValue("refreshToken", String.valueOf(user.getId()), refreshToken, JWTProvider.REFRESH_EXP_MILLI);

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
}