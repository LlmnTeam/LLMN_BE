package com.example.llmn.service;

import com.example.llmn.controller.DTO.MetricDTO;
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
    private final RedisService redisService;
    private final SystemInfo systemInfo = new SystemInfo();
    private final CentralProcessor processor = systemInfo.getHardware().getProcessor();

    // 첫 번째 호출에서 CPU ticks 값을 저장
    private long[] oldTicks = processor.getSystemCpuLoadTicks();
    public static final Long VALID_EXP = 1000L * 60 * 60 * 24 * 7; // 일주일
    private static final String PREVIOUS_BYTES_RECEIVED = "previousBytesReceived";
    private static final String PREVIOUS_BYTES_SENT = "previousBytesSent";
    private static final String DAILY_START_BYTES_RECEIVED = "dailyStartBytesReceived";
    private static final String DAILY_START_BYTES_SENT = "dailyStartBytesSent";

    @Scheduled(cron = "0 0/10 * * * *")
    public void collectMetrics() {
        Map<String, Object> metrics = gatherMetrics();

        Metric metric = Metric.builder()
                .cpuUsage((double) metrics.get("cpuUsage"))
                .totalMemory((long) metrics.get("totalMemory"))
                .usedMemory((long) metrics.get("usedMemory"))
                .totalBytesReceived((long) metrics.get("networkReceived")) // 10분 간격의 네트워크 트래픽
                .totalBytesSent((long) metrics.get("networkSent"))
                .build();

        metricRepository.save(metric);
    }

    public MetricResponse.FindCurrentMetricDTO findCurrentMetric() {
        Map<String, Object> metrics = gatherMetrics();
        Map<String, Long> dailyTraffic = getDailyTraffic();

        return new MetricResponse.FindCurrentMetricDTO(
                (double) metrics.get("cpuUsage"),
                (long) metrics.get("totalMemory"),
                (long) metrics.get("usedMemory"),
                dailyTraffic.get("dailyReceived"), // 하루동안 누적 네트워크 트래픽
                dailyTraffic.get("dailySent")
        );
    }

    @Transactional(readOnly = true)
    public MetricResponse.FindMetricHistoryDTO findMetricHistory(int minusHour){
        LocalDateTime now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        List<Metric> metrics = metricRepository.findALlWithinDate(now.minusHours(minusHour));

        // 시간 형식 "HH:mm"
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");

        // CPU 데이터
        List<MetricResponse.CpuMetricDTO> cpuMetricDTOS = metrics.stream()
                .map(metric -> new MetricResponse.CpuMetricDTO(
                        metric.getCreatedDate().format(formatter),
                        metric.getCpuUsage()))
                .toList();

        // 메모리 사용량은 퍼센티지로 변환
        List<MetricResponse.MemoryMetricDTO> memoryMetricDTOS = metrics.stream()
                .map(metric -> {
                    String time = metric.getCreatedDate().format(formatter);
                    long memoryUsage = metric.getUsedMemory() / (1024 * 1024);
                    return new MetricResponse.MemoryMetricDTO(time, memoryUsage);
                })
                .toList();

        // 네트워크 수신
        List<MetricResponse.NetworkInMetricDTO> networkInMetricDTOS = metrics.stream()
                .map(metric -> new MetricResponse.NetworkInMetricDTO(
                        metric.getCreatedDate().format(formatter),
                        metric.getTotalBytesReceived()))
                .toList();

        // 네트워크 송신
        List<MetricResponse.NetworkOutMetricDTO> networkOutMetricDTOS = metrics.stream()
                .map(metric -> new MetricResponse.NetworkOutMetricDTO(
                        metric.getCreatedDate().format(formatter),
                        metric.getTotalBytesSent()
                ))
                .toList();

        return new MetricResponse.FindMetricHistoryDTO(cpuMetricDTOS, memoryMetricDTOS, networkInMetricDTOS, networkOutMetricDTOS);
    }

    // 하루 시작 시점에 네트워크 트래픽 값을 저장 (매일 자정에 실행)
    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyTraffic() {
        MetricDTO.NetworkTraffic totalNetworkTraffic = getTotalNetworkTraffic();
        redisService.storeValue(DAILY_START_BYTES_RECEIVED, String.valueOf(totalNetworkTraffic.bytesReceived()), VALID_EXP);
        redisService.storeValue(DAILY_START_BYTES_SENT, String.valueOf(totalNetworkTraffic.bytesSent()), VALID_EXP);
    }

    // 하루 동안의 누적 네트워크 트래픽 계산
    private Map<String, Long> getDailyTraffic() {
        // 오늘 0시의 네트워크 트래픽값
        long dailyStartBytesReceived = redisService.getDataInLong(DAILY_START_BYTES_RECEIVED);
        long dailyStartBytesSent = redisService.getDataInLong(DAILY_START_BYTES_SENT);

        MetricDTO.NetworkTraffic totalNetworkTraffic = getTotalNetworkTraffic();
        long dailyReceived = totalNetworkTraffic.bytesReceived() - dailyStartBytesReceived;
        long dailySent = totalNetworkTraffic.bytesSent() - dailyStartBytesSent;

        Map<String, Long> dailyTraffic = new HashMap<>();
        dailyTraffic.put("dailyReceived", dailyReceived);
        dailyTraffic.put("dailySent", dailySent);

        return dailyTraffic;
    }

    // 네트워크 트래픽의 수신 및 송신 바이트를 계산
    private MetricDTO.NetworkTraffic getTotalNetworkTraffic() {
        List<NetworkIF> networkIFs = systemInfo.getHardware().getNetworkIFs();
        long totalBytesReceived = 0;
        long totalBytesSent = 0;

        for (NetworkIF net : networkIFs) {
            net.updateAttributes();
            totalBytesReceived += net.getBytesRecv();
            totalBytesSent += net.getBytesSent();
        }

        // 바이트 → MB 변환 (1024 * 1024)
        long totalBytesReceivedInMB = totalBytesReceived / (1024 * 1024);
        long totalBytesSentInMB = totalBytesSent / (1024 * 1024);

        return new MetricDTO.NetworkTraffic(totalBytesReceivedInMB, totalBytesSentInMB);
    }

    private Map<String, Object> gatherMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        GlobalMemory memory = systemInfo.getHardware().getMemory();

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
        MetricDTO.NetworkTraffic totalNetworkTraffic = getTotalNetworkTraffic();

        // 이전에 저장된 값과의 차이로 네트워크 트래픽 계산
        Long previousBytesReceived = redisService.getDataInLong(PREVIOUS_BYTES_RECEIVED);
        Long previousBytesSent = redisService.getDataInLong(PREVIOUS_BYTES_SENT);

        long bytesReceived = totalNetworkTraffic.bytesReceived() - previousBytesReceived;
        long bytesSent = totalNetworkTraffic.bytesSent() - previousBytesSent;

        // 현재 값을 다음 계산에 사용할 수 있도록 저장
        redisService.storeValue(PREVIOUS_BYTES_RECEIVED, String.valueOf(totalNetworkTraffic.bytesReceived()), VALID_EXP);
        redisService.storeValue(PREVIOUS_BYTES_SENT, String.valueOf(totalNetworkTraffic.bytesSent()), VALID_EXP);

        // 구간 동안의 네트워크 트래픽을 저장
        metrics.put("networkReceived", bytesReceived);
        metrics.put("networkSent", bytesSent);

        return metrics;
    }
}