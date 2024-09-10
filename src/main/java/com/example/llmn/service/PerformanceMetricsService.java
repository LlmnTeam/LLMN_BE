package com.example.llmn.service;

import com.example.llmn.domain.Metric;
import com.example.llmn.repository.MetricRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.NetworkIF;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PerformanceMetricsService {

    private final MetricRepository metricRepository;
    private final SystemInfo systemInfo = new SystemInfo();
    private final CentralProcessor processor = systemInfo.getHardware().getProcessor();

    // 첫 번째 호출에서 CPU ticks 값을 저장하기 위한 필드
    private long[] oldTicks = processor.getSystemCpuLoadTicks();

    @Scheduled(fixedRate = 1800000)  // 30분 마다 실행
    public void collectMetrics() {
        GlobalMemory memory = systemInfo.getHardware().getMemory();
        List<NetworkIF> networkIFs = systemInfo.getHardware().getNetworkIFs();

        // CPU 사용량 계산
        long[] newTicks = processor.getSystemCpuLoadTicks();
        double cpuLoad = processor.getSystemCpuLoadBetweenTicks(oldTicks); // 이전 tick 값으로 CPU 로드 계산
        oldTicks = newTicks;

        // 메모리 사용량 계산
        long totalMemory = memory.getTotal();
        long usedMemory = totalMemory - memory.getAvailable();

        // 네트워크 트래픽 계산
        long totalBytesReceived = 0;
        long totalBytesSent = 0;
        for (NetworkIF net : networkIFs) {
            net.updateAttributes();
            totalBytesReceived += net.getBytesRecv();
            totalBytesSent += net.getBytesSent();
        }

        Metric metric = Metric.builder()
                .cpuUsage(cpuLoad * 100)
                .totalMemory(totalMemory)
                .usedMemory(usedMemory)
                .totalBytesReceived(totalBytesReceived)
                .totalBytesSent(totalBytesSent)
                .build();

        metricRepository.save(metric);
    }
}