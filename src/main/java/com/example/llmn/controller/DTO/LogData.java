package com.example.llmn.controller.DTO;

import java.time.Instant;

public record LogData(
        String containerName,
        Instant timestamp,
        String message
) {}