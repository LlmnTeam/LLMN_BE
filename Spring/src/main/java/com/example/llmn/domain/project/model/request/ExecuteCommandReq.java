package com.example.llmn.domain.project.model.request;

import jakarta.validation.constraints.NotBlank;

public record ExecuteCommandReq(
        @NotBlank(message="명령을 입력해주세요.")
        String command,
        @NotBlank(message="SSH 호스트명을 입력해주세요.")
        String sshHost,
        boolean isFirstExecution) {
}