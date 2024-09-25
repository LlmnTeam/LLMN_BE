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

    private static final String REDIS_KEY_NETWORK_REC = "network:received";
    private static final String REDIS_KEY_NETWORK_TRANS = "network:transmitted";
    private static final String METRIC_KEY = "metric";
    private static final Long METRIC_EXP = 10 * 60 * 1000L; // 10분
    private static final boolean UPDATE_CACHE = true;
    private static final boolean NOT_UPDATE_CACHE = false;
    private static final String METRIC_MAP_CPU_USAGE = "cpuUsage";
    private static final String METRIC_MAP_TOTAL_MEMORY = "totalMemory";
    private static final String METRIC_MAP_USED_MEMORY = "usedMemory";
    private static final String METRIC_MAP_NETWORK_REC = "networkReceived";
    private static final String METRIC_MAP_NETWORK_SENT ="networkSent";
    private static final String METRIC_MAP_DAILY_NET_REC = "dailyReceived";
    private static final String METRIC_MAP_DAILY_NET_SENT ="dailySent";
    private static final String COMMAND_TOP = "top -b -n1 | grep \"Cpu(s)\\|Mem\"";
    private static final String COMMAND_NETWORK_USAGE = "cat /proc/net/dev | grep eth0";
    private static final String CPU_USAGE_PREFIX = "%Cpu(s):";
    private static final String MEM_USAGE_PREFIX = "MiB Mem";
    public static final String NUMERIC_REGEX = "[^0-9.]";

    @Scheduled(cron = "0 0/10 * * * *")
    public void collectMetrics() throws Exception {
        List<Long> userIds = userRepository.findIds();

        for(Long userId : userIds){
            User userRef = userRepository.getReferenceById(userId);

            Map<String, Double> topMetrics = collectTopMetrics(userId);
            Map<String, Double> networkMetrics = collectNetworkMetrics(userId, UPDATE_CACHE);

            Metric metric = Metric.builder()
                    .user(userRef)
                    .cpuUsage(topMetrics.get(METRIC_MAP_CPU_USAGE))
                    .totalMemory(topMetrics.get(METRIC_MAP_TOTAL_MEMORY))
                    .usedMemory(topMetrics.get(METRIC_MAP_USED_MEMORY))
                    .totalBytesReceived(networkMetrics.get(METRIC_MAP_NETWORK_REC)) // 10분 간격의 네트워크 트래픽
                    .totalBytesSent(networkMetrics.get(METRIC_MAP_NETWORK_SENT))
                    .build();

            metricRepository.save(metric);
        }
    }

    public Map<String, Double> collectTopMetrics(Long userId) throws Exception {
        Map<String, Double> metricsMap = new HashMap<>();

        // CPU와 메모리 사용량을 동시에 얻기 위해 top 명령어 실행
        String commandResponse = sshService.executeCommandOnce(COMMAND_TOP, userId);

        // 명령어 응답 파싱
        String[] lines = commandResponse.split("\n");

        for (String line : lines) {
            line = line.trim();

            // CPU 사용량 라인 처리 (%Cpu(s): 0.0 us, 6.2 sy, 0.0 ni, 93.8 id, 0.0 wa, 0.0 hi, 0.0 si, 0.0 st)
            if (line.startsWith(CPU_USAGE_PREFIX)) {
                // 쉼표를 기준으로 분리
                String[] cpuParts = line.split(",");

                // us 값과 sy 값 추출
                String usUsage = cpuParts[0].split(":")[1]
                        .trim()
                        .replaceAll(NUMERIC_REGEX, "")
                        .trim();
                String syUsage = cpuParts[1]
                        .trim()
                        .replaceAll(NUMERIC_REGEX, "")
                        .trim();

                // us와 sy를 합쳐서 CPU 부하량 계산
                double cpuUsage = Double.parseDouble(usUsage) + Double.parseDouble(syUsage);
                metricsMap.put(METRIC_MAP_CPU_USAGE, cpuUsage);
            }

            // 메모리 사용량 라인 처리 (MiB Mem : 949.2 total, 141.4 free, 325.1 used, 482.8 buff/cache)
            if (line.startsWith(MEM_USAGE_PREFIX)) {
                String[] memParts = line.split(",");

                // 전체 메모리와 사용 메모리 추출
                String memTotal = memParts[0].replaceAll(NUMERIC_REGEX, "").trim();
                String memUsed = memParts[2].replaceAll(NUMERIC_REGEX, "").trim();

                metricsMap.put(METRIC_MAP_TOTAL_MEMORY, Double.parseDouble(memTotal));
                metricsMap.put(METRIC_MAP_USED_MEMORY, Double.parseDouble(memUsed));
            }
        }

        return metricsMap;
    }

    // 네트워크 송수신량을 수집하고 레디스에 저장
    public Map<String, Double> collectNetworkMetrics(Long userId, boolean updateCache) throws Exception {
        Map<String, Double> metricsMap = new HashMap<>();

        // 현재 네트워크 사용량 조회
        Map<String, Double> currentNetworkMetric = collectNetworkUsage(userId);

        // 레디스에서 이전 네트워크 사용량 조회 (없으면 0.0)
        Double previousReceived = redisService.getDataInDouble(REDIS_KEY_NETWORK_REC);
        Double previousTransmitted = redisService.getDataInDouble(REDIS_KEY_NETWORK_TRANS);

        // 특정 기간 동안의 네트워크 사용량 계산 (설정한 기간에 따라 달라짐)
        double receivedDiff = currentNetworkMetric.get(METRIC_MAP_NETWORK_REC) - previousReceived;
        double transmittedDiff = currentNetworkMetric.get(METRIC_MAP_NETWORK_SENT) - previousTransmitted;

        metricsMap.put(METRIC_MAP_NETWORK_REC, receivedDiff);
        metricsMap.put(METRIC_MAP_NETWORK_SENT, transmittedDiff);

        // 업데이트 플래그가 존재 => 현재 네트워크 사용량으로 업데이트
        if(updateCache) {
            redisService.storeValue(REDIS_KEY_NETWORK_REC, String.valueOf(currentNetworkMetric.get(METRIC_MAP_NETWORK_REC)));
            redisService.storeValue(REDIS_KEY_NETWORK_TRANS, String.valueOf(currentNetworkMetric.get(METRIC_MAP_NETWORK_SENT)));
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
                topMetrics.get(METRIC_MAP_CPU_USAGE),
                topMetrics.get(METRIC_MAP_TOTAL_MEMORY),
                topMetrics.get(METRIC_MAP_USED_MEMORY),
                networkMetrics.get(METRIC_MAP_NETWORK_REC),
                networkMetrics.get(METRIC_MAP_NETWORK_SENT)
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
        dailyTraffic.put(METRIC_MAP_DAILY_NET_REC, (long) dailyReceived);
        dailyTraffic.put(METRIC_MAP_DAILY_NET_SENT, (long) dailySent);

        return dailyTraffic;
    }

    // 네트워크 송수신량 수집
    private Map<String, Double> collectNetworkUsage(Long userId) throws Exception {
        // 네트워크 송/수신 기록 조회 명령어 실행
        String commandResponse = sshService.executeCommandOnce(COMMAND_NETWORK_USAGE, userId);

        // eth0: 905961216 722194  0  0  0  0  0  0  21378581  162883  0  0  0  0  0  0
        String[] parts = commandResponse.trim().split("\\s+");

        Map<String, Double> networkUsageMap = new HashMap<>();
        if (parts.length >= 10) {
            long receivedBytes = Long.parseLong(parts[1]);  // 수신된 바이트
            long transmittedBytes = Long.parseLong(parts[9]);  // 송신된 바이트

            // 바이트를 MB로 변환
            double receivedMB = receivedBytes / (1024.0 * 1024.0);
            double transmittedMB = transmittedBytes / (1024.0 * 1024.0);

            networkUsageMap.put(METRIC_MAP_NETWORK_REC, receivedMB);
            networkUsageMap.put(METRIC_MAP_NETWORK_SENT, transmittedMB);
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