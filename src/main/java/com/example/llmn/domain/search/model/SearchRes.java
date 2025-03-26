package com.example.llmn.domain.search.model;

import java.util.List;

public record SearchRes(
        List<LogFileDTO> logfiles,
        List<InsightDTO> insights) {
}
