package com.example.llmn.domain.project.model.response;

public record SummaryDTO(
        Long id,
        String time,
        String content,
        boolean isChecked) {
}
