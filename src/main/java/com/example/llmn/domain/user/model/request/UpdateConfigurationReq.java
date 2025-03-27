package com.example.llmn.domain.user.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateConfigurationReq(
        @NotBlank(message="닉네임을 입력해주세요.")
        @Size(min=2, max=20, message = "닉네임은 2자에서 20자 이내여야 합니다.")
        String nickName,

        List<SshInfoReq> sshInfos,

        boolean receivingAlarm,

        @NotBlank(message = "모니터링할 클라우드 인스턴스를 선택해야 합니다.")
        String monitoringSshHost
) {}
