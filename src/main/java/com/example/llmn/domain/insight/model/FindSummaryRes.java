package com.example.llmn.domain.insight.model;

import com.example.llmn.domain.summary.Summary;

import java.util.List;

public record FindSummaryRes(List<SummaryDTO> summaries) {

    public static FindSummaryRes from(List<Summary> summaries) {
        List<SummaryDTO> summaryDTOS = summaries.stream()
                .map(SummaryDTO::from)
                .toList();
        return new FindSummaryRes(summaryDTOS);
    }
}
