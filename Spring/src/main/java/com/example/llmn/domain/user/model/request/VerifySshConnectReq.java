package com.example.llmn.domain.user.model.request;

import jakarta.validation.constraints.NotBlank;

public record VerifySshConnectReq(
        @NotBlank(message = "클라우드 인스턴스의 유저명을 입력해주세요.")
        String remoteName,

        @NotBlank(message = "클라우드 인스턴의 IP(호스트)를 입력해주세요.")
        String remoteHost,

        @NotBlank(message = "Pem키 파일의 경로를 입력해주세요.")
        String remoteKeyPath
) {}
