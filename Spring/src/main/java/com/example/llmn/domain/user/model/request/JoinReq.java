package com.example.llmn.domain.user.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record JoinReq(
        @NotBlank(message = "이메일을 입력해주세요.")
        @Pattern(regexp = "^[\\w._%+-]+@[\\w.-]+\\.[a-zA-Z]{2,6}$", message = "올바른 이메일 형식을 입력해주세요.")
        String email,

        @NotBlank(message="닉네임을 입력해주세요.")
        @Size(min=2, max=20, message = "닉네임은 2자에서 20자 이내여야 합니다.")
        String nickName,

        @NotBlank(message = "비밀번호를 입력해주세요.")
        @Size(min=8, max=20, message = "비밀번호는 8자에서 20자 이내여야 합니다.")
        @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d)(?=.*[@#$%^&+=!~`<>,./?;:'\"\\[\\]{}\\\\()|_-])\\S*$", message = "올바른 비밀번호 형식을 입력해주세요.")
        String password,

        @NotBlank(message = "비밀번호를 입력해주세요.")
        String passwordConfirm,

        List<SshInfoReq> sshInfos,

        boolean receivingAlarm,

        @NotBlank(message = "모니터링할 클라우드 인스턴스를 선택해야 합니다.")
        String monitoringSshHost,

        @NotBlank(message = "OpenAI API키를 입력해야 합니다.")
        String openAiKey
) {}