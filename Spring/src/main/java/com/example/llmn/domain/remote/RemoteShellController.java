package com.example.llmn.domain.remote;

import com.example.llmn.domain.project.model.request.ExecuteCommandReq;
import com.example.llmn.security.userdetails.CustomUserDetails;
import com.example.llmn.common.utils.ApiUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class RemoteShellController {

    private final SecureShellManager secureShellManager;
    private final SshConfigService sshConfigService;

    @PostMapping("/command/init")
    public ResponseEntity<?> initCommend(@AuthenticationPrincipal CustomUserDetails userDetails) {
        secureShellManager.initializeShellSession(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PostMapping("/command/home")
    public ResponseEntity<?> executeCommandInShell(@RequestBody ExecuteCommandReq requestDTO, @AuthenticationPrincipal CustomUserDetails userDetails) {
        String response = secureShellManager.executeShellCommand(requestDTO.command(), requestDTO.isFirstExecution(), userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, response));
    }

    @PostMapping("/command/terminate")
    public ResponseEntity<?> terminateCommand(@AuthenticationPrincipal CustomUserDetails userDetails) {
        secureShellManager.terminateShellSession(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PostMapping("/command/interrupt")
    public ResponseEntity<?> interruptCommand(@AuthenticationPrincipal CustomUserDetails userDetails) {
        secureShellManager.sendInterruptSignal(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PostMapping("/accounts/ssh")
    public ResponseEntity<?> uploadSSHKey(@RequestParam("file") MultipartFile file) {
        Path path = sshConfigService.uploadSSHKey(file);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.CREATED, path));
    }
}
