package com.example.llmn.domain.user;

import com.example.llmn.domain.user.model.request.*;
import com.example.llmn.domain.user.model.response.*;
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
    public ResponseEntity<?> resendCode(@RequestBody @Valid UserRequest.EmailDTO requestDTO, @RequestParam String codeType) {
        userService.sendCodeByEmail(requestDTO.email(), codeType);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PostMapping("/accounts/validate/ssh")
    public ResponseEntity<?> verifySshConnect(@RequestBody @Valid VerifySshConnectReq requestDTO){
        VerifySshConnectRes responseDTO = userService.verifySshConnect(requestDTO);
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
    public ResponseEntity<?> updateMonitoringSsh(@RequestBody @Valid UserRequest.UpdateMonitoringSshDTO requestDTO, @AuthenticationPrincipal CustomUserDetails userDetails) {
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
    public ResponseEntity<?> updateApiKey(@RequestBody @Valid UserRequest.UpdateAPiKeyDTO requestDTO){
        userService.updateApiKey(requestDTO.apiKey());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PostMapping("/accounts/recovery/code")
    public ResponseEntity<?> sendCodeForRecovery(@RequestBody @Valid EmailReq requestDTO) {
        CheckAccountExistRes responseDTO = userService.checkLocalAccountExist(requestDTO);
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
        Long userId = userService.validateAccessTokenInRedis(accessToken);
        ValidateAccessTokenRes responseDTO = userService.findNickName(userId);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }
}
