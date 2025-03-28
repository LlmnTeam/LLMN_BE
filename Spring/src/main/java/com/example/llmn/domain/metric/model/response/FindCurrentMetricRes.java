package com.example.llmn.domain.metric.model.response;

import java.util.Map;

import static com.example.llmn.domain.metric.MetricConstants.*;

public record FindCurrentMetricRes(
        double cpuUsage,
        double totalMemory,
        double usedMemory,
        double networkReceived,
        double networkSent) {

    private static final double DEFAULT_METRIC_VALUE = 0.0;

    public static FindCurrentMetricRes from(Map<String, Double> cpuAndMemoryMetrics, Map<String, Double> networkMetrics) {
        return new FindCurrentMetricRes(
                cpuAndMemoryMetrics.getOrDefault(KEY_CPU_USAGE, DEFAULT_METRIC_VALUE),
                cpuAndMemoryMetrics.getOrDefault(KEY_TOTAL_MEMORY, DEFAULT_METRIC_VALUE),
                cpuAndMemoryMetrics.getOrDefault(KEY_USED_MEMORY, DEFAULT_METRIC_VALUE),
                networkMetrics.getOrDefault(KEY_NETWORK_RECEIVED, DEFAULT_METRIC_VALUE),
                networkMetrics.getOrDefault(KEY_NETWORK_SENT, DEFAULT_METRIC_VALUE)
        );
    }
}