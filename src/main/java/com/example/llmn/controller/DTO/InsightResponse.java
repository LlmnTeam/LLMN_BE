package com.example.llmn.controller.DTO;

import java.time.LocalDateTime;
import java.util.List;

public class InsightResponse {

    public record FindInsightHomeDTO(
            String performanceSummary,
            String dailySummary,
            String trendSummary,
            String recommendation){}

    public record FindPerformanceSummaryDTO(List<PerformanceSummaryDTO> performanceSummaries){}

    public record PerformanceSummaryDTO(LocalDateTime time, String content){}
}
