package com.example.llmn.integration.email;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MailTemplate {
    VERIFICATION_CODE("[LLMN] 이메일 인증 코드입니다.", "인증 코드는 다음과 같습니다: %s\n이 코드를 입력하여 이메일을 인증해 주세요."),
    TEMPORARY_PASSWORD("[LLMN] 임시 비밀번호 입니다.", "임시 비밀번호: %s\n로그인 후 비밀번호를 변경해 주세요."),
    ACCOUNT_SUSPENSION("[LLMN] 로그인 횟수를 초과하여 계정이 비활성화 되었습니다.", "계정 보안을 위해 24시간 후에 로그인을 시도하실 수 있습니다. 보안을 위해 비밀번호를 변경해주세요");

    private final String subject;
    private final String textTemplate;

    public String formatText(String... args) {
        return String.format(textTemplate, (Object[]) args);
    }
}