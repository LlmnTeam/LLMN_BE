package com.example.llmn.controller.DTO;

import java.time.LocalDateTime;
import java.util.List;

public class InsightResponse {

    public record FindInsightHomeDTO(
            String performanceSummary,
            LocalDateTime performanceUpdateTime,
            String dailySummary,
            LocalDateTime dailyUpdateTime,
            String trendSummary,
            LocalDateTime trendUpdateTime,
            String recommendation,
            LocalDateTime recommendUpdateTime){}

    public record FindPerformanceSummaryDTO(List<PerformanceSummaryDTO> performanceSummaries){}

    public record PerformanceSummaryDTO(String time, String content){}
}
