package com.example.llmn.controller.DTO;

public class LogDTO {

    public record SummaryRequestDTO(String cotent){}

    public record SummaryResponseDTO(String generalSummary, String anomalySummary){}

    public record PerformanceSummaryResponseDTO(String performanceSummary){}

    public record DailySummaryResponseDTO(String dailySummary){}
}
