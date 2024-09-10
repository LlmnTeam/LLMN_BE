package com.example.llmn.service;

import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.NetworkIF;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PerformanceMetricsService {

    private final SystemInfo systemInfo = new SystemInfo();
    private final CentralProcessor processor = systemInfo.getHardware().getProcessor();

    // 첫 번째 호출에서 CPU ticks 값을 저장하기 위한 필드
    private long[] oldTicks = processor.getSystemCpuLoadTicks();

    public Map<String, Object> collectMetrics() {
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