package com.example.llmn.controller.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UserRequest {

    public record LoginDTO(
            @NotBlank(message = "이메일을 입력해주세요.")
            @Pattern(regexp = "^[\\w._%+-]+@[\\w.-]+\\.[a-zA-Z]{2,6}$", message = "올바른 이메일 형식을 입력해주세요.")
            String email,

            @NotBlank(message = "비밀번호를 입력해주세요.")
            @Size(min=8, max=20, message = "비밀번호는 8자에서 20자 이내여야 합니다.")
            @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d)(?=.*[@#$%^&+=!~`<>,./?;:'\"\\[\\]{}\\\\()|_-])\\S*$", message = "올바른 비밀번호 형식을 입력해주세요.")
            String password
    ){}

        public record JoinDTO(
                @NotBlank(message = "이메일을 입력해주세요.")
                @Pattern(regexp = "^[\\w._%+-]+@[\\w.-]+\\.[a-zA-Z]{2,6}$", message = "올바른 이메일 형식을 입력해주세요.")
                String email,
                @NotBlank(message="닉네을 입력해주세요.")
                @Size(min=2, max=20, message = "닉네임은 2자에서 20자 이내여야 합니다.")
                String nickName,
                @NotBlank(message = "비밀번호를 입력해주세요.")
                @Size(min=8, max=20, message = "비밀번호는 8자에서 20자 이내여야 합니다.")
                @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d)(?=.*[@#$%^&+=!~`<>,./?;:'\"\\[\\]{}\\\\()|_-])\\S*$", message = "올바른 비밀번호 형식을 입력해주세요.")
                String password,
                @NotBlank(message = "비밀번호를 입력해주세요.")
                String passwordConfirm) {}
}
