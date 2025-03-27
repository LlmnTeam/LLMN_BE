package com.example.llmn.domain.user.model.request;

import jakarta.validation.constraints.NotBlank;

public record CheckNickReq(
        @NotBlank(message = "닉네임을 입력해주세요.")
        String nickName
) {}
