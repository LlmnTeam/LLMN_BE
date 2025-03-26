package com.example.llmn.domain.project.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProjectReq(
        @NotBlank(message="서비스 이름을 입력해주세요.")
        @Size(min=2, max=20, message = "서비스 이름은 2자에서 20자 이내여야 합니다.")
        String projectName,
        String containerName,
        @NotBlank(message="설명을 입력해주세요.")
        String description) {
}
