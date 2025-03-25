package com.example.llmn.domain.metric.model.response;

import com.example.llmn.common.utils.DateTimeUtils;
import com.example.llmn.domain.metric.Metric;

import java.util.ArrayList;
import java.util.List;

import static com.example.llmn.common.utils.DateTimeUtils.HOUR_MINUTE_FORMATTER;

public record FindMetricHistoryRes(
        List<CpuMetricRes> cpuMetrics,
        List<MemoryMetricRes> memoryMetrics,
        List<NetworkInMetricRes> networkInMetrics,
        List<NetworkOutMetricRes> networkOutMetrics) {

    public static FindMetricHistoryRes from(List<Metric> metrics) {
        List<CpuMetricRes> cpuMetricResList = new ArrayList<>();
        List<MemoryMetricRes> memoryMetricResList = new ArrayList<>();
        List<NetworkInMetricRes> networkInMetricResList = new ArrayList<>();
        List<NetworkOutMetricRes> networkOutMetricResList = new ArrayList<>();

        metrics.forEach(metric -> {
            String time = DateTimeUtils.formatLocalDateTime(metric.getCreatedDate(), HOUR_MINUTE_FORMATTER);
            cpuMetricResList.add(CpuMetricRes.from(metric, time));
            memoryMetricResList.add(MemoryMetricRes.from(metric, time));
            networkInMetricResList.add(NetworkInMetricRes.from(metric, time));
            networkOutMetricResList.add(NetworkOutMetricRes.from(metric, time));
        });

        return new FindMetricHistoryRes(cpuMetricResList, memoryMetricResList, networkInMetricResList, networkOutMetricResList);
    }
}
