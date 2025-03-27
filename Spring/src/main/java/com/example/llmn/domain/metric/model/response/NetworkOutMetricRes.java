package com.example.llmn.domain.metric.model.response;

import com.example.llmn.domain.metric.Metric;

public record NetworkOutMetricRes(
        String time,
        double networkSent) {

    public static NetworkOutMetricRes from(Metric metric, String formattedTime) {
        double networkSent = Math.round(metric.getTotalBytesSent() * 1000.0) / 1000.0;
        return new NetworkOutMetricRes(formattedTime, networkSent);
    }
}
