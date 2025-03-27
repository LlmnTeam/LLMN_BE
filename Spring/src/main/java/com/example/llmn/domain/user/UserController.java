package com.example.llmn.domain.user;

import com.example.llmn.domain.user.model.request.*;
import com.example.llmn.domain.user.model.response.*;
import com.example.llmn.security.userdetails.CustomUserDetails;
import com.example.llmn.common.utils.ApiUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.example.llmn.integration.email.EmailConstants.CODE_TYPE_JOIN;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class UserController {

    private final UserService userService;

    @PostMapping("/accounts")
    public ResponseEntity<?> join(@RequestBody @Valid JoinReq requestDTO){
        userService.join(requestDTO);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.CREATED, null));
    }

    @PostMapping("/accounts/check/email")
    public ResponseEntity<?> checkEmailAndSendCode(@RequestBody @Valid EmailReq requestDTO) {
        CheckEmailExistRes responseDTO = userService.checkEmailExist(requestDTO.email());
        if (responseDTO.isValid()) userService.sendCodeByEmail(requestDTO.email(), CODE_TYPE_JOIN);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/accounts/check/nick")
    public ResponseEntity<?> checkNickname(@RequestBody @Valid CheckNickReq requestDTO){
        CheckNickNameRes responseDTO = userService.checkNickNameDuplicate(requestDTO);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/accounts/verify/code")
    public ResponseEntity<?> verifyCode(@RequestBody @Valid VerifyCodeReq requestDTO, @RequestParam String codeType){
        VerifyEmailCodeRes responseDTO = userService.verifyCode(requestDTO, codeType);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/accounts/resend/code")
    public ResponseEntity<?> resendCode(@RequestBody @Valid EmailReq requestDTO, @RequestParam String codeType) {
        userService.sendCodeByEmail(requestDTO.email(), codeType);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PostMapping("/accounts/validate/ssh")
    public ResponseEntity<?> checkSshConnect(@RequestBody @Valid VerifySshConnectReq requestDTO){
        VerifySshConnectRes responseDTO = userService.checkSshConnect(requestDTO);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/accounts/validate/key")
    public ResponseEntity<?> validateOpenAIKey(@RequestBody @Valid ValidateOpenAIKeyReq requestDTO){
        ValidateOpenAIKeyRes responseDTO = userService.validateOpenAIKey(requestDTO.apiKey());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/cloud")
    public ResponseEntity<?> findCloudInfo(@AuthenticationPrincipal CustomUserDetails userDetails) {
        FindCloudInfoRes responseDTO = userService.findCloudInfo(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PatchMapping("/cloud")
    public ResponseEntity<?> updateMonitoringSsh(@RequestBody @Valid UpdateMonitoringSshReq requestDTO, @AuthenticationPrincipal CustomUserDetails userDetails) {
        userService.updateMonitoringSsh(userDetails.getUser().getId(), requestDTO.remoteHost());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @GetMapping("/accounts/info")
    public ResponseEntity<?> findConfigurationInfo(@AuthenticationPrincipal CustomUserDetails userDetails) {
        FindConfigurationInfoRes responseDTO = userService.findConfigurationInfo(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PatchMapping("/accounts")
    public ResponseEntity<?> updateConfiguration(@RequestBody @Valid UpdateConfigurationReq requestDTO, @AuthenticationPrincipal CustomUserDetails userDetails) {
        userService.updateConfiguration(requestDTO, userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PatchMapping("/accounts/apiKey")
    public ResponseEntity<?> updateApiKey(@RequestBody @Valid UpdateApiKeyReq requestDTO){
        userService.updateOpenAIKey(requestDTO.apiKey(), requestDTO.email());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PostMapping("/accounts/recovery/code")
    public ResponseEntity<?> sendCodeForRecovery(@RequestBody @Valid EmailReq requestDTO) {
        CheckAccountExistRes responseDTO = userService.checkAccountExist(requestDTO);
        if (responseDTO.isValid()) userService.sendCodeByEmail(requestDTO.email(), CODE_TYPE_JOIN);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/accounts/recovery/reset")
    public ResponseEntity<?> resetPassword(@RequestBody @Valid ResetPasswordReq requestDTO){
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
        Long userId = userService.validateAccessToken(accessToken);
        ValidateAccessTokenRes responseDTO = userService.findNickName(userId);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }
}
