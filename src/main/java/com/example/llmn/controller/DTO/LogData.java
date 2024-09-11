package com.example.llmn.controller.DTO;

import java.time.Instant;

public record LogData(
        String serviceName,
        Instant timestamp,
        String message,
        boolean isProcessed,
        String logLevel
) {}