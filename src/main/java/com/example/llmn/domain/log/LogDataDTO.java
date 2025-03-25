package com.example.llmn.domain.log;

import java.time.Instant;

public record LogDataDTO(
        String serviceName,
        Instant timestamp,
        String message,
        boolean isProcessed,
        String logLevel
) {}