package com.example.llmn.controller;

import com.example.llmn.controller.DTO.UserRequest;
import com.example.llmn.controller.DTO.UserResponse;
import com.example.llmn.core.security.CustomUserDetails;
import com.example.llmn.core.utils.ApiUtils;
import com.example.llmn.service.UserService;
import jakarta.mail.MessagingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class UserController {

    private final UserService userService;
    private static final String CODE_TYPE_JOIN = "join";

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody @Valid UserRequest.LoginDTO requestDTO, HttpServletRequest request) {
        Map<String, String> tokens = userService.login(requestDTO, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, userService.createRefreshTokenCookie(tokens.get("refreshToken")))
                .body(ApiUtils.success(HttpStatus.OK, new UserResponse.LoginDTO(tokens.get("accessToken"))));
    }

    @PostMapping("/accounts")
    public ResponseEntity<?> join(@RequestBody @Valid UserRequest.JoinDTO requestDTO){
        userService.join(requestDTO);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.CREATED, null));
    }

    @PostMapping("/ssh")
    public ResponseEntity<?> uploadSSHKey(@RequestParam("file") MultipartFile file) {
        try {
            Path path = userService.uploadSSHKey(file);
            return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.CREATED, path));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok().body(ApiUtils.error(e.getMessage(), HttpStatus.BAD_REQUEST));
        } catch (FileAlreadyExistsException e) {
            return ResponseEntity.ok().body(ApiUtils.error(e.getMessage(), HttpStatus.CONFLICT));
        } catch (IOException e) {
            return ResponseEntity.ok().body(ApiUtils.error("파일 업로드 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    @PostMapping("/accounts/check/email")
    public ResponseEntity<?> checkEmailAndSendCode(@RequestBody @Valid UserRequest.EmailDTO requestDTO) {
        UserResponse.CheckEmailExistDTO responseDTO = userService.checkEmailExist(requestDTO.email());
        userService.sendCodeWithValidation(requestDTO.email(), CODE_TYPE_JOIN, responseDTO.isValid());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/accounts/verify/code")
    public ResponseEntity<?> verifyCode(@RequestBody @Valid UserRequest.VerifyCodeDTO requestDTO){
        UserResponse.VerifyEmailCodeDTO responseDTO = userService.verifyCode(requestDTO);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/home")
    public ResponseEntity<?> findDashboard(@AuthenticationPrincipal CustomUserDetails userDetails) throws Exception {
        UserResponse.FindDashboardDTO responseDTO = userService.findDashboard(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/accounts/info")
    public ResponseEntity<?> findConfigurationInfo(@AuthenticationPrincipal CustomUserDetails userDetails) {
        UserResponse.FindConfigurationInfoDTO responseDTO = userService.findConfigurationInfo(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PatchMapping("/accounts")
    public ResponseEntity<?> updateConfiguration(@RequestBody @Valid UserRequest.UpdateConfigurationDTO requestDTO, @AuthenticationPrincipal CustomUserDetails userDetails) throws Exception {
        userService.updateConfiguration(requestDTO, userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }
}
