package com.example.llmn.admin;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.user.User;
import com.example.llmn.domain.user.UserRepository;
import com.example.llmn.domain.user.model.UserRequest;
import com.example.llmn.integration.redis.RedisService;
import com.example.llmn.security.JWTProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static com.example.llmn.integration.redis.RedisConstants.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final RedisService redisService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public Map<String, String> login(UserRequest.LoginDTO requestDTO) {
        User user = userRepository.findByEmail(requestDTO.email()).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_ACCOUNT_WRONG)
        );

        if (isPasswordUnmatched(requestDTO.password(), user.getPassword())) {
            throw new CustomException(ExceptionCode.USER_ACCOUNT_WRONG);
        }

        return createToken(user);
    }

    private boolean isPasswordUnmatched(String requestPassword, String userPassword) {
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
}
