package com.example.llmn.core.utils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.util.Arrays;

public class CookieUtils {

    private CookieUtils() {}

    public static String getCookieFromRequest(String cookieKey, HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return null;
        }

        return Arrays.stream(cookies)
                .filter(cookie -> cookieKey.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    public static void setCookieToResponse(String cookieKey, String cookieValue, Long maxAgeSec, boolean secureCookie, boolean isHttpOnly, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(cookieKey, cookieValue)
                .httpOnly(isHttpOnly)
                .secure(secureCookie)
                .path("/")
                .sameSite("Lax")
                .maxAge(maxAgeSec)
                .build();

        // 쿠키를 응답 헤더에 추가 (기존에 쿠키가 있더라도 새로 추가)
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public static HttpServletResponse syncHttpResponseCookiesFromHttpRequest(HttpServletRequest request, HttpServletResponse response, String... exceptionKeys) {
        Cookie[] cookies = request.getCookies();

        if (cookies != null) {
            for (Cookie cookie : cookies) {
                String cookieKey = cookie.getName();

                // exceptionKey 배열에 포함된 쿠키 키는 제외
                if (Arrays.asList(exceptionKeys).contains(cookieKey)) {
                    continue;
                }

                ResponseCookie responseCookie = ResponseCookie.from(cookieKey, cookie.getValue())
                        .httpOnly(cookie.isHttpOnly())
                        .secure(cookie.getSecure())
                        .maxAge(cookie.getMaxAge())
                        .path("/")
                        .sameSite("None")
                        .build();

                response.addHeader(HttpHeaders.SET_COOKIE, responseCookie.toString());
            }
        }
        return response;
    }
}
