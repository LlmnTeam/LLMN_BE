package com.example.llmn.controller.DTO;

public class LogDTO {

    public record SummaryRequestDTO(String logMessage){}

    public record SummaryResponseDTO(String generalSummary, String anomalySummary){}
}
