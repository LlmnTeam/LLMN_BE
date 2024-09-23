package com.example.llmn.controller.DTO;

public class LogDTO {

    public record SummaryRequestDTO(String content){}

    public record SummaryResponseDTO(String generalSummary, String anomalySummary, boolean isUrgent){}

    public record PerformanceSummaryResponseDTO(String performanceSummary){}

    public record DailySummaryResponseDTO(String dailySummary){}

    public record HourlySummaryResponseDTO(String hourlySummary){}

    public record TrendSummaryResponseDTO(String trendSummary){}

    public record RecommendationDTO(String recommend){}
}
