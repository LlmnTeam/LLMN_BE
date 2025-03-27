package com.example.llmn.domain.metric;

import java.util.List;

public class MetricResponse {

    public record FindMetricHistoryDTO(
            List<CpuMetricDTO> cpuMetrics,
            List<MemoryMetricDTO> memoryMetrics,
            List<NetworkInMetricDTO> networkInMetrics,
            List<NetworkOutMetricDTO> networkOutMetrics) {}

    public record CpuMetricDTO(
            String time,
            double cpuUsage) {}

    public record MemoryMetricDTO(
            String time,
            double memoryUsage) {}

    public record NetworkInMetricDTO(
            String time,
            double networkReceived) {}

    public record NetworkOutMetricDTO(
            String time,
            double networkSent) {}

    public record FindCurrentMetricDTO(
            double cpuUsage,
            double totalMemory,
            double usedMemory,
            double networkReceived,
            double networkSent){}

    public record CommandDTO(
            String privateKeyPath,
            String host,
            String username,
            String command){}
}
