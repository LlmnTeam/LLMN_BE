package com.example.llmn.controller.DTO;

import com.example.llmn.domain.ContainerStatus;

import java.time.LocalDateTime;
import java.util.List;

public class MetricResponse {

    public record FindMetricHistoryDTO(
            List<CpuMetricDTO> cpuMetrics,
            List<MemoryMetricDTO> memoryMetrics) {}

    public record CpuMetricDTO(
            String time,
            double cpuUsage) {}
    public record MemoryMetricDTO(
            String time,
            double usedMemoryPercentage) {}
}
