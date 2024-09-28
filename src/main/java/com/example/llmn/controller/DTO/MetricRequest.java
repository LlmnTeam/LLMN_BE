package com.example.llmn.controller.DTO;

import jakarta.validation.constraints.NotBlank;

public class MetricRequest {

    public record ExecuteCommandDTO(
            @NotBlank(message="명령을 입력해주세요.")
            String command,
            @NotBlank(message="SSH 호스트명을 입력해주세요.")
            String sshHost){}
}
