package com.example.llmn.domain.user;

import com.example.llmn.security.userdetails.CustomUserDetails;
import com.example.llmn.common.utils.ApiUtils;
import com.example.llmn.domain.user.model.UserRequest;
import com.example.llmn.domain.user.model.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Map;

import static com.example.llmn.common.utils.CookieUtils.createRefreshTokenCookie;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class UserController {

    private final UserService userService;
    private static final String CODE_TYPE_JOIN = "join";

    @PostMapping("/accounts")
    public ResponseEntity<?> join(@RequestBody @Valid UserRequest.JoinDTO requestDTO){
        userService.join(requestDTO);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.CREATED, null));
    }

    @PostMapping("/accounts/ssh")
    public ResponseEntity<?> uploadSSHKey(@RequestParam("file") MultipartFile file) {
        Path path = userService.uploadSSHKey(file);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.CREATED, path));
    }

    @PostMapping("/accounts/check/email")
    public ResponseEntity<?> checkEmailAndSendCode(@RequestBody @Valid UserRequest.EmailDTO requestDTO) {
        UserResponse.CheckEmailExistDTO responseDTO = userService.checkEmailExist(requestDTO.email());
        if (responseDTO.isValid()) userService.sendCodeByEmail(requestDTO.email(), CODE_TYPE_JOIN);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/accounts/check/nick")
    public ResponseEntity<?> checkNickname(@RequestBody @Valid UserRequest.CheckNickDTO requestDTO){
        UserResponse.CheckNickNameDTO responseDTO = userService.checkNickNameDuplicate(requestDTO);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/accounts/verify/code")
    public ResponseEntity<?> verifyCode(@RequestBody @Valid UserRequest.VerifyCodeDTO requestDTO, @RequestParam String codeType){
        UserResponse.VerifyEmailCodeDTO responseDTO = userService.verifyCode(requestDTO, codeType);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/accounts/resend/code")
    public ResponseEntity<?> resendCode(@RequestBody @Valid UserRequest.EmailDTO requestDTO, @RequestParam String codeType) {
        userService.sendCodeByEmail(requestDTO.email(), codeType);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PostMapping("/accounts/validate/ssh")
    public ResponseEntity<?> verifySshConnect(@RequestBody @Valid UserRequest.VerifySshConnectDTO requestDTO){
        UserResponse.VerifySshConnectDTO responseDTO = userService.verifySshConnect(requestDTO);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/accounts/validate/key")
    public ResponseEntity<?> validateOpenAIKey(@RequestBody @Valid UserRequest.ValidateOpenAIKeyDTO requestDTO){
        UserResponse.ValidateOpenAIKeyDTO responseDTO = userService.validateOpenAIKey(requestDTO.apiKey());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/cloud")
    public ResponseEntity<?> findCloudInfo(@AuthenticationPrincipal CustomUserDetails userDetails) {
        UserResponse.FindCloudInfoDTO responseDTO = userService.findCloudInfo(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PatchMapping("/cloud")
    public ResponseEntity<?> updateMonitoringSsh(@RequestBody @Valid UserRequest.UpdateMonitoringSshDTO requestDTO, @AuthenticationPrincipal CustomUserDetails userDetails) {
        userService.updateMonitoringSsh(userDetails.getUser().getId(), requestDTO.remoteHost());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @GetMapping("/accounts/info")
    public ResponseEntity<?> findConfigurationInfo(@AuthenticationPrincipal CustomUserDetails userDetails) {
        UserResponse.FindConfigurationInfoDTO responseDTO = userService.findConfigurationInfo(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PatchMapping("/accounts")
    public ResponseEntity<?> updateConfiguration(@RequestBody @Valid UserRequest.UpdateConfigurationDTO requestDTO, @AuthenticationPrincipal CustomUserDetails userDetails) {
        userService.updateConfiguration(requestDTO, userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PatchMapping("/accounts/apiKey")
    public ResponseEntity<?> updateApiKey(@RequestBody @Valid UserRequest.UpdateAPiKeyDTO requestDTO){
        userService.updateApiKey(requestDTO.apiKey());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PostMapping("/accounts/recovery/code")
    public ResponseEntity<?> sendCodeForRecovery(@RequestBody @Valid UserRequest.EmailDTO requestDTO) {
        UserResponse.CheckAccountExistDTO responseDTO = userService.checkLocalAccountExist(requestDTO);
        if (responseDTO.isValid()) userService.sendCodeByEmail(requestDTO.email(), CODE_TYPE_JOIN);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/accounts/recovery/reset")
    public ResponseEntity<?> resetPassword(@RequestBody @Valid UserRequest.ResetPasswordDTO requestDTO){
        userService.resetPassword(requestDTO);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @DeleteMapping("/accounts/withdraw")
    public ResponseEntity<?> withdrawMember(@AuthenticationPrincipal CustomUserDetails userDetails){
        userService.withdrawMember(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    // nickName을 응답으로 주는 이유는, 토큰 검증 시 닉네임도 함께 받고 싶다는 프론트의 요구사항 (SSR을 사용할 때 필요하고 함)
    @PostMapping("/validate/accessToken")
    public ResponseEntity<?> validateAccessToken(@CookieValue String accessToken){
        Long userId = userService.validateAccessTokenInRedis(accessToken);
        UserResponse.ValidateAccessTokenDTO responseDTO = userService.findNickName(userId);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }
}
