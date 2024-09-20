package com.example.llmn.controller.DTO;

import com.example.llmn.domain.ContainerStatus;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
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
            long memoryUsage) {}

    public record NetworkInMetricDTO(
            String time,
            double networkReceived) {}

    public record NetworkOutMetricDTO(
            String time,
            double networkSent) {}

    public record FindCurrentMetricDTO(
            double cpuUsage,
            long totalMemory,
            long usedMemory,
            long networkReceived,
            long networkSent){}

    public record CommandDTO(
            String privateKeyPath,
            String host,
            String username,
            String command){}
}
