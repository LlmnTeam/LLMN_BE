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

    public record FindPerformanceSummaryDTO(List<SummaryDTO> summaries){}

    public record FindDailySummaryDTO(List<SummaryDTO> summaries){}

    public record SummaryDTO(String time, String content){}
}
