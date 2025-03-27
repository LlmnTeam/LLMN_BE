package com.example.llmn.domain.user.model.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateMonitoringSshReq(
        @NotBlank(message = "모니터링할 호스트(ip)를 입력해주세요.")
        String remoteHost
) {}
