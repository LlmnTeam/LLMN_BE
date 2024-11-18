package com.example.llmn.controller;

import com.example.llmn.controller.DTO.ProjectRequest;
import com.example.llmn.core.security.CustomUserDetails;
import com.example.llmn.core.utils.ApiUtils;
import com.example.llmn.service.SSHService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class SshController {

    private final SSHService sshService;

    @PostMapping("/command/init")
    public ResponseEntity<?> initCommend(@AuthenticationPrincipal CustomUserDetails userDetails){
        sshService.initCommend(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PostMapping("/command/home")
    public ResponseEntity<?> executeCommandInShell(@RequestBody ProjectRequest.ExecuteCommandDTO requestDTO, @AuthenticationPrincipal CustomUserDetails userDetails) {
        String response = sshService.executeCommandInShell(requestDTO.command(), requestDTO.isFirstExecution(), userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, response));
    }

    @PostMapping("/command/terminate")
    public ResponseEntity<?> terminateCommand(@AuthenticationPrincipal CustomUserDetails userDetails) {
        sshService.stopCommend(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PostMapping("/command/interrupt")
    public ResponseEntity<?> interruptCommand(@AuthenticationPrincipal CustomUserDetails userDetails) {
        sshService.executeSigInt(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }
}
