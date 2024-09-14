package com.example.llmn.controller.DTO;

public class InsightResponse {

    public record FindInsightHomeDTO(
            String performanceSummary,
            String dailySummary,
            String trendSummary,
            String recommendation){}
}
