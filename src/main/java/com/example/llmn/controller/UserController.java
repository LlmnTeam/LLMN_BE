package com.example.llmn.controller;

import com.example.llmn.controller.DTO.UserRequest;
import com.example.llmn.controller.DTO.UserResponse;
import com.example.llmn.core.utils.ApiUtils;
import com.example.llmn.service.UserService;
import jakarta.mail.MessagingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class UserController {

    private final UserService userService;
    private static final String UPLOAD_DIR = "ssh";
    private static final String CODE_TYPE_JOIN = "join";

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody @Valid UserRequest.LoginDTO requestDTO, HttpServletRequest request) throws MessagingException {
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

    @PostMapping("/upload")
    public ResponseEntity<?> uploadSSHKey(@RequestParam("file") MultipartFile file) {
        // 파일이 빈 경우 오류 처리
        if (file.isEmpty()) {
            return ResponseEntity.ok().body(ApiUtils.error("파일이 없습니다.", HttpStatus.BAD_REQUEST));
        }

        try {
            // 디렉토리가 없으면 생성
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath); // 디렉토리가 없으면 생성
            }

            String fileName = file.getOriginalFilename();
            Path path = Paths.get(UPLOAD_DIR + File.separator + fileName);

            // 파일이 이미 존재하는지 확인
            if (Files.exists(path)) {
                return ResponseEntity.ok().body(ApiUtils.error("파일이 이미 존재합니다.", HttpStatus.CONFLICT));
            }

            Files.write(path, file.getBytes());

            return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.CREATED, path));
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.ok().body(ApiUtils.error("파일 업로드 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    @PostMapping("/accounts/check/email")
    public ResponseEntity<?> checkEmailAndSendCode(@RequestBody @Valid UserRequest.EmailDTO requestDTO) throws MessagingException {
        UserResponse.CheckEmailExistDTO responseDTO = userService.checkEmailExist(requestDTO.email());
        userService.sendCodeWithValidation(requestDTO.email(), CODE_TYPE_JOIN, responseDTO.isValid());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }
}
