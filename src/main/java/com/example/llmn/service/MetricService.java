package com.example.llmn.service;

import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.domain.Metric;

import com.example.llmn.domain.User;
import com.example.llmn.repository.MetricRepository;
import com.example.llmn.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MetricService {

    private final MetricRepository metricRepository;
    private final UserRepository userRepository;
    private final RedisService redisService;
    private final SSHService sshService;
    private final ObjectMapper objectMapper;

    private static final String NETWORK_RECEIVED_KEY = "network:received";
    private static final String NETWORK_TRANSMITTED_KEY = "network:transmitted";
    private static final String METRIC_KEY = "metric";
    private static final Long METRIC_EXP = 10 * 60 * 1000L; // 10분
    private static final boolean UPDATE_CACHE = true;
    private static final boolean NOT_UPDATE_CACHE = false;

    @Scheduled(cron = "0 0/10 * * * *")
    public void collectMetrics() throws Exception {
        List<Long> userIds = userRepository.findIds();

        for(Long userId : userIds){
            User userRef = userRepository.getReferenceById(userId);

            Map<String, Double> topMetrics = collectTopMetrics(userId);
            Map<String, Double> networkMetrics = collectNetworkMetrics(userId, UPDATE_CACHE);

            Metric metric = Metric.builder()
                    .user(userRef)
                    .cpuUsage(topMetrics.get("cpuUsage"))
                    .totalMemory(topMetrics.get("totalMemory"))
                    .usedMemory(topMetrics.get("usedMemory"))
                    .totalBytesReceived(networkMetrics.get("networkReceived")) // 10분 간격의 네트워크 트래픽
                    .totalBytesSent(networkMetrics.get("networkSent"))
                    .build();

            metricRepository.save(metric);
        }
    }

    public Map<String, Double> collectTopMetrics(Long userId) throws Exception {
        Map<String, Double> metricsMap = new HashMap<>();

        // CPU와 메모리 사용량을 동시에 얻기 위한 top 명령어 실행
        String command = "top -b -n1 | grep \"Cpu(s)\\|Mem\"";
        String commandResponse = sshService.executeCommandOnce(command, userId);

        // 명령어 응답 파싱
        String[] lines = commandResponse.split("\n");

        for (String line : lines) {
            line = line.trim();

            // CPU 사용량 라인 처리
            if (line.startsWith("%Cpu(s):")) {
                // 쉼표를 기준으로 분리
                String[] cpuParts = line.split(",");

                // "Cpu(s):" 제거 후 공백과 문자를 제거하여 숫자만 추출
                String usUsage = cpuParts[0].split(":")[1].trim().replaceAll("[^0-9.]", "").trim();
                String syUsage = cpuParts[1].trim().replaceAll("[^0-9.]", "").trim();

                // us와 sy를 합쳐서 CPU 부하량 계산
                double cpuUsage = Double.parseDouble(usUsage) + Double.parseDouble(syUsage);
                metricsMap.put("cpuUsage", cpuUsage);
            }

            // 메모리 사용량 라인 처리
            if (line.startsWith("MiB Mem")) {
                String[] memParts = line.split(",");

                // 전체 메모리와 사용 메모리 추출
                String memTotal = memParts[0].replaceAll("[^0-9.]", "").trim();
                String memUsed = memParts[2].replaceAll("[^0-9.]", "").trim();

                metricsMap.put("totalMemory", Double.parseDouble(memTotal));
                metricsMap.put("usedMemory", Double.parseDouble(memUsed));
            }
        }

        return metricsMap;
    }

    // 네트워크 송수신량을 수집하고 레디스에 저장
    public Map<String, Double> collectNetworkMetrics(Long userId, boolean updateCache) throws Exception {
        Map<String, Double> metricsMap = new HashMap<>();

        // 현재 네트워크 사용량 조회
        Map<String, Double> currentUsage = collectNetworkUsage(userId);

        // 레디스에서 이전 네트워크 사용량 조회 (없으면 0.0)
        Double previousReceived = redisService.getDataInDouble(NETWORK_RECEIVED_KEY);
        Double previousTransmitted = redisService.getDataInDouble(NETWORK_TRANSMITTED_KEY);

        // 특정 기간 동안의 네트워크 사용량 계산 (설정한 기간에 따라 달라짐)
        double receivedDiff = currentUsage.get("networkReceived") - previousReceived;
        double transmittedDiff = currentUsage.get("networkSent") - previousTransmitted;

        metricsMap.put("networkReceived", receivedDiff);
        metricsMap.put("networkSent", transmittedDiff);

        // 업데이트 플래그가 존재 => 현재 네트워크 사용량으로 업데이트
        if(updateCache) {
            redisService.storeValue(NETWORK_RECEIVED_KEY, String.valueOf(currentUsage.get("networkReceived")));
            redisService.storeValue(NETWORK_TRANSMITTED_KEY, String.valueOf(currentUsage.get("networkSent")));
        }

        return metricsMap;
    }

    public MetricResponse.FindCurrentMetricDTO findCurrentMetric(Long userId) throws Exception {
        // 1. 레디스에서 캐시된 값을 먼저 조회
        MetricResponse.FindCurrentMetricDTO cachedMetric = getCachedMetric(userId);
        if (cachedMetric != null) {
            return cachedMetric;
        }

        // 2. 캐시된 값이 없으면 새로운 Metric 수집 후 저장
        Map<String, Double> topMetrics = collectTopMetrics(userId);
        Map<String, Double> networkMetrics = collectNetworkMetrics(userId, NOT_UPDATE_CACHE);

        MetricResponse.FindCurrentMetricDTO metricDTO = new MetricResponse.FindCurrentMetricDTO(
                topMetrics.get("cpuUsage"),
                topMetrics.get("totalMemory"),
                topMetrics.get("usedMemory"),
                networkMetrics.get("networkReceived"),
                networkMetrics.get("networkSent")
        );

        // 유효 시간은 10분
        redisService.storeValue(METRIC_KEY, userId.toString(), objectMapper.writeValueAsString(metricDTO), METRIC_EXP);

        return metricDTO;
    }

    @Transactional(readOnly = true)
    public MetricResponse.FindMetricHistoryDTO findMetricHistory(int minusHour, Long userId){
        // minusHour 내 지표들을 모두 가져옴
        LocalDateTime now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        List<Metric> metrics = metricRepository.findALlWithinDate(now.minusHours(minusHour), userId);

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

                    double memoryUsage = metric.getTotalMemory() > 0
                            ? metric.getUsedMemory() / metric.getTotalMemory() * 100
                            : 0.0;

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

    // 하루 동안의 누적 네트워크 트래픽 계산
    public Map<String, Long> getTodayNetworkTraffic(Long userId) {
        // minusHour 내 지표들을 모두 가져옴
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<Metric> metrics = metricRepository.findALlWithinDate(todayStart, userId);

        double dailyReceived = metrics.stream()
                .mapToDouble(Metric::getTotalBytesReceived)
                .sum();

        double dailySent = metrics.stream()
                .mapToDouble(Metric::getTotalBytesSent)
                .sum();

        Map<String, Long> dailyTraffic = new HashMap<>();
        dailyTraffic.put("dailyReceived", (long) dailyReceived);
        dailyTraffic.put("dailySent", (long) dailySent);

        return dailyTraffic;
    }

    // 네트워크 송수신량 수집
    private Map<String, Double> collectNetworkUsage(Long userId) throws Exception {
        String command = "cat /proc/net/dev | grep eth0";
        String commandResponse = sshService.executeCommandOnce(command, userId);

        String[] parts = commandResponse.trim().split("\\s+");

        Map<String, Double> networkUsageMap = new HashMap<>();
        if (parts.length >= 10) {
            long receivedBytes = Long.parseLong(parts[1]);  // 수신된 바이트
            long transmittedBytes = Long.parseLong(parts[9]);  // 송신된 바이트

            // 바이트를 MB로 변환
            double receivedMB = receivedBytes / (1024.0 * 1024.0);
            double transmittedMB = transmittedBytes / (1024.0 * 1024.0);

            networkUsageMap.put("networkReceived", receivedMB);
            networkUsageMap.put("networkSent", transmittedMB);
        }

        return networkUsageMap;
    }

    // 레디스에서 Metric을 가져오는 메서드
    private MetricResponse.FindCurrentMetricDTO getCachedMetric(Long userId) {
        String cachedValue = redisService.getDataInStr(METRIC_KEY, userId.toString());

        // 캐시된 값이 없으면 null 반환
        if (cachedValue == null) {
            return null;
        }

        return convertStringToMetricDTO(cachedValue);
    }

    private MetricResponse.FindCurrentMetricDTO convertStringToMetricDTO(String cachedValue) {
        try {
            return objectMapper.readValue(cachedValue, MetricResponse.FindCurrentMetricDTO.class);
        } catch (JsonProcessingException e) {;
            return null; // 변환에 실패한 경우 null 반환
        }
    }
}