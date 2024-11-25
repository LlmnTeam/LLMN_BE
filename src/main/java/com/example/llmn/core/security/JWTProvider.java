package com.example.llmn.core.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.llmn.domain.User;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class JWTProvider {

    private JWTProvider() {}

    public static final Long ACCESS_EXP_MILLI = 1000L * 60 * 60 * 24; // 1시간
    public static final Long REFRESH_EXP_MILLI = 1000L * 60 * 60 * 24 * 7; // 일주일
    public static final Long REFRESH_EXP_SEC = 60L * 60 * 24 * 7; // 일주일
    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String SECRET = "MySecretKey";

    public static String createAccessToken(User user) {
        return create(user, ACCESS_EXP_MILLI);
    }

    public static String createRefreshToken(User user) {
        return create(user, REFRESH_EXP_MILLI);
    }

    public static String create(User user, Long exp) {
        return JWT.create()
                .withSubject(user.getEmail())
                .withExpiresAt(new Date(System.currentTimeMillis() + exp))
                .withClaim("id", user.getId())
                .withClaim("nickName", user.getNickName())
                .sign(Algorithm.HMAC512(SECRET));
    }

    public static DecodedJWT decodeJWT(String jwt) throws SignatureVerificationException, TokenExpiredException {
        jwt = removeTokenPrefix(jwt);
        return JWT.require(Algorithm.HMAC512(SECRET))
                .build()
                .verify(jwt);
    }

    public static boolean isInvalidJwtFormat(String jwt) {
        try {
            decodeJWT(jwt); // 디코딩 해보고 잘못된 형식이면 예외가 발생하는 것을 이용
            return false;
        } catch (JWTVerificationException exception) {
            return true;
        }
    }

    public static Long extractUserIdFromToken(String token) {
        DecodedJWT decodedJWT = decodeJWT(token);
        return decodedJWT.getClaim("id").asLong();
    }

    private static String removeTokenPrefix(String jwt) {
        return jwt.replace(JWTProvider.TOKEN_PREFIX, ""); // "Bearer " 접두사가 있다면 제거
    }
}
