package com.example.llmn.common.utils;

import com.example.llmn.security.JWTProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.util.Arrays;
import java.util.Set;

public class CookieUtils {

    private CookieUtils() {}

    private static final String KEY_REFRESH_TOKEN = "refreshToken";

    public static String createRefreshTokenCookie(String refreshToken, boolean httpOnly, boolean secure) {
        return ResponseCookie.from(KEY_REFRESH_TOKEN, refreshToken)
                .httpOnly(httpOnly)
                .secure(secure)
                .path("/")
                .sameSite("Lax")
                .maxAge(JWTProvider.REFRESH_EXP_SEC)
                .build()
                .toString();
    }

    public static void setCookieToResponse(String name, String value, Long maxAgeSec, boolean secure, boolean httpOnly, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(secure)
                .path("/")
                .sameSite("Lax")
                .maxAge(maxAgeSec)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public static void syncRequestCookiesToResponse(HttpServletRequest request, HttpServletResponse response, Set<String> excludedCookies) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return;
        }

        Arrays.stream(cookies)
                .filter(cookie -> isCookieIncluded(cookie, excludedCookies))
                .map(CookieUtils::convertToResponseCookie)
                .forEach(responseCookie -> response.addHeader(HttpHeaders.SET_COOKIE, responseCookie.toString()));
    }

    public static String getCookieFromRequest(String name, HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        return Arrays.stream(cookies)
                .filter(targetCookie -> isCookieSame(name, targetCookie))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private static boolean isCookieIncluded(Cookie cookie, Set<String> excludedCookies) {
        return !excludedCookies.contains(cookie.getName());
    }

    private static ResponseCookie convertToResponseCookie(Cookie cookie) {
        return ResponseCookie.from(cookie.getName(), cookie.getValue())
                .httpOnly(cookie.isHttpOnly())
                .secure(cookie.getSecure())
                .maxAge(cookie.getMaxAge())
                .path("/")
                .sameSite("Lax")
                .build();
    }

    private static boolean isCookieSame(String cookieName, Cookie targetCookie) {
        return cookieName.equals(targetCookie.getName());
    }
}
