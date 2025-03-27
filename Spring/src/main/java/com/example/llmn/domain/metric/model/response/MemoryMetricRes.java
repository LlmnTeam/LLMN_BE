package com.example.llmn.domain.metric.model.response;

import com.example.llmn.domain.metric.Metric;

public record MemoryMetricRes(
        String time,
        double memoryUsage) {

    public static MemoryMetricRes from(Metric metric, String formattedTime) {
        double memoryUsage = metric.getTotalMemory() > 0 ?
                Math.round((metric.getUsedMemory() / metric.getTotalMemory() * 100) * 1000.0) / 1000.0 : 0.0;
        return new MemoryMetricRes(formattedTime, memoryUsage);
    }
}
