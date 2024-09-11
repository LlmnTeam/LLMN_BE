package com.example.llmn.service;

import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.domain.Metric;
import com.example.llmn.repository.MetricRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.NetworkIF;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MetricService {

    private final MetricRepository metricRepository;
    private final SystemInfo systemInfo = new SystemInfo();
    private final CentralProcessor processor = systemInfo.getHardware().getProcessor();

    // 첫 번째 호출에서 CPU ticks 값을 저장하기 위한 필드
    private long[] oldTicks = processor.getSystemCpuLoadTicks();

    @Scheduled(cron = "0 0/10 * * * *")
    public void collectMetrics() {
        Map<String, Object> metrics = gatherMetrics();

        Metric metric = Metric.builder()
                .cpuUsage((double) metrics.get("cpuUsage"))
                .totalMemory((long) metrics.get("totalMemory"))
                .usedMemory((long) metrics.get("usedMemory"))
                .totalBytesReceived((long) metrics.get("networkReceived"))
                .totalBytesSent((long) metrics.get("networkSent"))
                .build();

        metricRepository.save(metric);
    }

    public Map<String, Object> findCurrentMetric() {
        return gatherMetrics();
    }

    @Transactional(readOnly = true)
    public MetricResponse.FindMetricHistoryDTO findMetricHistory(){
        LocalDateTime now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        List<Metric> metrics = metricRepository.findALlWithinDate(now.minusDays(1));

        // 시간 형식 "HH:mm"
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");

        // CPU 데이터를 DTO로 변환하여 리스트로 반환
        List<MetricResponse.CpuMetricDTO> cpuMetricDTOS = metrics.stream()
                .map(metric -> new MetricResponse.CpuMetricDTO(
                        metric.getCreatedDate().format(formatter),
                        metric.getCpuUsage()))
                .toList();

        // 메모리 사용량 퍼센티지로 변환하여 리스트로 반환
        List<MetricResponse.MemoryMetricDTO> memoryMetricDTOS = metrics.stream()
                .map(metric -> {
                    String time = metric.getCreatedDate().format(formatter);
                    double usedMemoryPercentage = ((double) metric.getUsedMemory() / metric.getTotalMemory()) * 100;
                    return new MetricResponse.MemoryMetricDTO(time, usedMemoryPercentage);
                })
                .toList();

        return new MetricResponse.FindMetricHistoryDTO(cpuMetricDTOS, memoryMetricDTOS);
    }

    private Map<String, Object> gatherMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        GlobalMemory memory = systemInfo.getHardware().getMemory();
        List<NetworkIF> networkIFs = systemInfo.getHardware().getNetworkIFs();

        // CPU 사용량 계산
        long[] newTicks = processor.getSystemCpuLoadTicks();
        double cpuLoad = processor.getSystemCpuLoadBetweenTicks(oldTicks); // 이전 tick 값으로 CPU 로드 계산
        oldTicks = newTicks; // 새로운 tick 값을 저장하여 다음 호출 시 사용
        metrics.put("cpuUsage", cpuLoad * 100);

        // 메모리 사용량 계산
        long totalMemory = memory.getTotal();
        long usedMemory = totalMemory - memory.getAvailable();
        metrics.put("totalMemory", totalMemory);
        metrics.put("usedMemory", usedMemory);

        // 네트워크 트래픽 계산
        long totalBytesReceived = 0;
        long totalBytesSent = 0;
        for (NetworkIF net : networkIFs) {
            net.updateAttributes();
            totalBytesReceived += net.getBytesRecv();
            totalBytesSent += net.getBytesSent();
        }
        metrics.put("networkReceived", totalBytesReceived);
        metrics.put("networkSent", totalBytesSent);

        return metrics;
    }
}