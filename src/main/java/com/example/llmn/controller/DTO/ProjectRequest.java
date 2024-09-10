package com.example.llmn.controller.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ProjectRequest {

    public record CreateProjectDTO(
            @NotBlank(message="닉네임을 입력해주세요.")
            @Size(min=2, max=20, message = "닉네임은 2자에서 20자 이내여야 합니다.")
            String name,

            @NotBlank(message="설명을 입력해주세요.")
            String description
    ){}
}
