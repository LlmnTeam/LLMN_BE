package com.example.llmn.domain.metric.model.response;

import com.example.llmn.domain.metric.Metric;

public record CpuMetricRes(
        String time,
        double cpuUsage) {

    public static CpuMetricRes from(Metric metric, String formattedTime) {
        Double cpuUsageVal = metric.getCpuUsage();
        double cpuUsage = (cpuUsageVal == null) ? 0
                : Math.round(cpuUsageVal * 1000.0) / 1000.0;
        return new CpuMetricRes(formattedTime, cpuUsage);
    }
}
