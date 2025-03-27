package com.example.llmn.domain.insight.model;

import com.example.llmn.domain.summary.Summary;

import static com.example.llmn.common.utils.DateTimeUtils.formatLocalDateTime;

public record SummaryDTO(
        Long id,
        String time,
        String content,
        boolean isChecked) {

    public static SummaryDTO from(Summary summary) {
        return new SummaryDTO(
                summary.getId(),
                formatLocalDateTime(summary.getCreatedDate()),
                summary.getContent(),
                summary.isChecked()
        );
    }
}
