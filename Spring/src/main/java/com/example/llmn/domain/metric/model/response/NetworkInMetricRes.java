package com.example.llmn.domain.metric.model.response;

import com.example.llmn.domain.metric.Metric;

public record NetworkInMetricRes(
        String time,
        double networkReceived) {

    public static NetworkInMetricRes from(Metric metric, String formattedTime) {
        double networkReceived = Math.round(metric.getTotalBytesReceived() * 1000.0) / 1000.0;
        return new NetworkInMetricRes(formattedTime, networkReceived);
    }
}