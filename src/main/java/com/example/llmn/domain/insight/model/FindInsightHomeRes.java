package com.example.llmn.domain.insight.model;

public record FindInsightHomeRes(
        String performanceSummary,
        String performanceUpdateTime,
        String dailySummary,
        String dailyUpdateTime,
        String trendSummary,
        String trendUpdateTime,
        String recommendation,
        String recommendUpdateTime) {
}
