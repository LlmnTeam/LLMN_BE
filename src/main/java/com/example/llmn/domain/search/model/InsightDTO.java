package com.example.llmn.domain.search.model;

import com.example.llmn.domain.summary.SummaryType;

public record InsightDTO(
        String projectName,
        String date,
        SummaryType type,
        String content) {
}