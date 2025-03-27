package com.example.llmn.domain.user.model.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateApiKeyReq(
        @NotBlank(message="API키를 입력해주세요.")
        String apiKey
) {}
