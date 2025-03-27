package com.example.llmn.admin;

import com.example.llmn.common.utils.ApiUtils;
import com.example.llmn.domain.user.model.request.LoginReq;
import com.example.llmn.domain.user.model.response.LoginRes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.example.llmn.common.constants.GlobalConstants.ACCESS_TOKEN;
import static com.example.llmn.common.constants.GlobalConstants.REFRESH_TOKEN;
import static com.example.llmn.common.utils.CookieUtils.createRefreshTokenCookie;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody @Valid LoginReq requestDTO) {
        Map<String, String> tokens = authService.login(requestDTO);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, createRefreshTokenCookie(tokens.get(REFRESH_TOKEN), true, false))
                .body(ApiUtils.success(HttpStatus.OK, new LoginRes(tokens.get(ACCESS_TOKEN))));
    }
}
