package com.example.llmn.domain.user.model.request;

import jakarta.validation.constraints.NotBlank;

public record ValidateOpenAIKeyReq(
        @NotBlank(message = "OpenAI API키를 입력해주세요.")
        String apiKey
) {}