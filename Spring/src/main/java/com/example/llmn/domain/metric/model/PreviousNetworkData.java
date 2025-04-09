package com.example.llmn.domain.metric.model;

public record PreviousNetworkData(
        double rxMB,
        double txMB,
        long timeMs) {
}
