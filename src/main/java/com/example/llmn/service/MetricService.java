package com.example.llmn.service;

import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.domain.Metric;

import com.example.llmn.domain.SshInfo;
import com.example.llmn.repository.MetricRepository;
import com.example.llmn.repository.SshInfoRepository;
import com.example.llmn.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class MetricService {

    private final MetricRepository metricRepository;
    private final UserRepository userRepository;
    private final SshInfoRepository sshInfoRepository;
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
    private static final String COMMAND_NETWORK_USAGE = "cat /proc/net/dev";
    private static final DateTimeFormatter formatterForHourAndMin = DateTimeFormatter.ofPattern("HH:mm"); // 시간 형식 "HH:mm"
    private static final Pattern CPU_PATTERN = Pattern.compile("%Cpu\\(s\\):\\s+([\\d.]+)\\s+us,\\s+([\\d.]+)\\s+sy,.*");
    private static final Pattern MEM_PATTERN = Pattern.compile("MiB Mem :\\s+([\\d.]+)\\s+total,\\s+([\\d.]+)\\s+free,\\s+([\\d.]+)\\s+used,.*");

    @Scheduled(cron = "0 0/10 * * * *")
    @Transactional
    public void collectMetrics() {
        List<Long> userIds = userRepository.findIds();

        for (Long userId : userIds) {
            // 사용자가 monitoringSshId를 설정했다면, 설정한 SSH 정보를 사용하여 지표를 수집
            List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
            userRepository.findMonitoringSshId(userId).ifPresent(monitoringSshId ->
                    sshInfos.forEach(sshInfo -> processMetrics(sshInfo, monitoringSshId))
            );
        }
    }

    public MetricResponse.FindCurrentMetricDTO findCurrentMetric(Long sshInfoId) {
        // 1. 레디스에서 캐시된 값을 먼저 조회
        MetricResponse.FindCurrentMetricDTO cachedMetric = getCachedMetric(sshInfoId);
        if (cachedMetric != null) {
            return cachedMetric;
        }

        // 2. 캐시된 값이 없음 => 새로운 Metric 수집
        Map<String, Double> cpuAndMemoryMetrics = collectCpuAndMemoryMetrics(sshInfoId);
        Map<String, Double> networkMetrics = collectNetworkMetrics(sshInfoId, NOT_UPDATE_CACHE);
        
        MetricResponse.FindCurrentMetricDTO metricDTO = new MetricResponse.FindCurrentMetricDTO(
                cpuAndMemoryMetrics.getOrDefault(METRIC_MAP_CPU_USAGE, 0.0),
                cpuAndMemoryMetrics.getOrDefault(METRIC_MAP_TOTAL_MEMORY, 0.0),
                cpuAndMemoryMetrics.getOrDefault(METRIC_MAP_USED_MEMORY, 0.0),
                networkMetrics.getOrDefault(METRIC_MAP_NETWORK_REC, 0.0),
                networkMetrics.getOrDefault(METRIC_MAP_NETWORK_SENT, 0.0)
        );

        // 3. 새로운 Metric 저장
        cacheMetricDTO(sshInfoId, metricDTO);

        return metricDTO;
    }

    @Transactional(readOnly = true)
    public MetricResponse.FindMetricHistoryDTO findMetricHistory(int minusHour, Long sshInfoId){
        // minusHour 내 지표들을 모두 가져옴
        LocalDateTime startTime = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0).minusHours(minusHour);
        List<Metric> metrics = metricRepository.findMetricsAfter(startTime, sshInfoId);

        // CPU, 메모리, 네트워크 수신/송신 지표를 담을 리스트
        List<MetricResponse.CpuMetricDTO> cpuMetricDTOS = new ArrayList<>();
        List<MetricResponse.MemoryMetricDTO> memoryMetricDTOS = new ArrayList<>();
        List<MetricResponse.NetworkInMetricDTO> networkInMetricDTOS = new ArrayList<>();
        List<MetricResponse.NetworkOutMetricDTO> networkOutMetricDTOS = new ArrayList<>();

        metrics.forEach(metric -> {
            String time = metric.getCreatedDate().format(formatterForHourAndMin);
            cpuMetricDTOS.add(createCpuMetricDTO(metric, time));
            memoryMetricDTOS.add(createMemoryMetricDTO(metric, time));
            networkInMetricDTOS.add(createNetworkInMetricDTO(metric, time));
            networkOutMetricDTOS.add(createNetworkOutMetricDTO(metric, time));
        });

        return new MetricResponse.FindMetricHistoryDTO(cpuMetricDTOS, memoryMetricDTOS, networkInMetricDTOS, networkOutMetricDTOS);
    }

    private Map<String, Double> collectCpuAndMemoryMetrics(Long sshInfoId) {
        Map<String, Double> metricsMap = new HashMap<>();

        // CPU와 메모리 사용량을 동시에 얻기 위해 top 명령어 실행
        String commandResponse = sshService.executeCommandOnce(COMMAND_TOP, sshInfoId);
        String[] lines = commandResponse.split("\n");

        for (String line : lines) {
            line = line.trim();
            parseAndStoreCpuUsage(metricsMap, line);
            parseAndStoreMemoryUsage(metricsMap, line);
        }

        return metricsMap;
    }

    private Map<String, Double> collectNetworkMetrics(Long sshInfoId, boolean updateCache) {
        // 1st 현재 네트워크 사용량 조회
        Map<String, Double> currentNetworkMetric = collectCurrentNetworkMetrics(sshInfoId);

        // 조회 실패 시 빈 맵 반환
        if(currentNetworkMetric.isEmpty()){
            return Collections.emptyMap();
        }

        // 2nd Redis에서 이전 네트워크 사용량 조회 (없으면 0.0 반환)
        Double previousReceived = redisService.getDataInDouble(REDIS_KEY_NETWORK_REC);
        Double previousTransmitted = redisService.getDataInDouble(REDIS_KEY_NETWORK_TRANS);

        // 3rd 네트워크 사용량 차이 계산
        Double receivedDiff = currentNetworkMetric.getOrDefault(METRIC_MAP_NETWORK_REC, 0.0) - previousReceived;
        Double transmittedDiff = currentNetworkMetric.getOrDefault(METRIC_MAP_NETWORK_SENT, 0.0) - previousTransmitted;

        Map<String, Double> metricsMap = new HashMap<>();
        metricsMap.put(METRIC_MAP_NETWORK_REC, receivedDiff);
        metricsMap.put(METRIC_MAP_NETWORK_SENT, transmittedDiff);

        // 캐시 업데이트
        if(updateCache) {
            updateNetworkCache(currentNetworkMetric);
        }

        return metricsMap;
    }

    // 하루 동안의 누적 네트워크 트래픽 계산
    private Map<String, Long> getTodayNetworkTraffic(Long sshInfoId) {
        // minusHour 내 지표들을 모두 가져옴
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<Metric> metrics = metricRepository.findMetricsAfter(todayStart, sshInfoId);

        Double dailyReceived = metrics.stream()
                .mapToDouble(Metric::getTotalBytesReceived)
                .sum();

        Double dailySent = metrics.stream()
                .mapToDouble(Metric::getTotalBytesSent)
                .sum();

        Map<String, Long> dailyTraffic = new HashMap<>();
        dailyTraffic.put(METRIC_MAP_DAILY_NET_REC, dailyReceived.longValue());
        dailyTraffic.put(METRIC_MAP_DAILY_NET_SENT, dailySent.longValue());

        return dailyTraffic;
    }

    // 조회 실패 => 비어있는 맵 반환
    private Map<String, Double> collectCurrentNetworkMetrics(Long sshInfoId) {
        String commandResponse = sshService.executeCommandOnce(COMMAND_NETWORK_USAGE, sshInfoId);

        Map<String, Double> networkUsageMap = new HashMap<>();

        // 주요 네트워크 인터페이스 패턴을 정규식으로 정의
        Pattern pattern = Pattern.compile("^(eth|ens|enp|wlan)\\S*:");

        String[] lines = commandResponse.split("\\n");
        for (String line : lines) {
            String trimmedLine = line.trim();

            // 패턴에 매칭되는 인터페이스인지 확인
            Matcher matcher = pattern.matcher(trimmedLine);
            if (matcher.find()) {
                String[] parts = trimmedLine.split("\\s+");
                if (parts.length >= 10) {
                    try {
                        long receivedBytes = Long.parseLong(parts[1]);  // 수신된 바이트
                        long transmittedBytes = Long.parseLong(parts[9]);  // 송신된 바이트

                        // 바이트를 MB로 변환
                        Double receivedMB = receivedBytes / (1024.0 * 1024.0);
                        Double transmittedMB = transmittedBytes / (1024.0 * 1024.0);

                        networkUsageMap.put(METRIC_MAP_NETWORK_REC, receivedMB);
                        networkUsageMap.put(METRIC_MAP_NETWORK_SENT, transmittedMB);
                        break;  // 최초로 찾은 유효한 인터페이스만 처리 후 종료
                    } catch (NumberFormatException e) {
                        log.error("네트워크 사용량 파싱 중 오류 발생: " + line + ", 오류 메시지: " + e.getMessage());
                    }
                }
            }
        }

        if (networkUsageMap.isEmpty()) {
            log.error("유효한 네트워크 인터페이스가 존재하지 않음.");
        }

        return networkUsageMap;
    }

    // 레디스에서 Metric을 가져오는 메서드
    private MetricResponse.FindCurrentMetricDTO getCachedMetric(Long sshInfoId) {
        String cachedValue = redisService.getDataInStr(METRIC_KEY, sshInfoId.toString());

        // 캐시된 값이 없으면 null 반환
        if (cachedValue == null) {
            return null;
        }

        return convertStringToMetricDTO(cachedValue);
    }

    private MetricResponse.FindCurrentMetricDTO convertStringToMetricDTO(String value) {
        try {
            return objectMapper.readValue(value, MetricResponse.FindCurrentMetricDTO.class);
        } catch (JsonProcessingException e) {;
            log.info("ObjectMapper 파싱 과정에서 에러 발생");
            return null; // 변환에 실패한 경우 null 반환
        }
    }

    private String convertMetricDtoToString(MetricResponse.FindCurrentMetricDTO metricDTO){
        try {
            return objectMapper.writeValueAsString(metricDTO);
        } catch (JsonProcessingException e) {
            log.info("ObjectMapper 파싱 과정에서 에러 발생");
            return "";
        }
    }

    private void cacheMetricDTO(Long sshInfoId, MetricResponse.FindCurrentMetricDTO metricDTO) {
        String value = convertMetricDtoToString(metricDTO);

        if (!value.isBlank()) {
            redisService.storeValue(METRIC_KEY, sshInfoId.toString(), value, METRIC_EXP);
        }
    }

    private void updateNetworkCache(Map<String, Double> currentNetworkMetric) {
        redisService.storeValue(REDIS_KEY_NETWORK_REC, String.valueOf(currentNetworkMetric.get(METRIC_MAP_NETWORK_REC)));
        redisService.storeValue(REDIS_KEY_NETWORK_TRANS, String.valueOf(currentNetworkMetric.get(METRIC_MAP_NETWORK_SENT)));
    }

    private void parseAndStoreCpuUsage(Map<String, Double> metricsMap, String line) {
        Matcher cpuMatcher = CPU_PATTERN.matcher(line);
        if (cpuMatcher.matches()) {
            Double usUsage = Double.parseDouble(cpuMatcher.group(1));
            Double syUsage = Double.parseDouble(cpuMatcher.group(2));
            Double cpuUsage = usUsage + syUsage;
            metricsMap.put(METRIC_MAP_CPU_USAGE, cpuUsage);
        }
    }

    private void parseAndStoreMemoryUsage(Map<String, Double> metricsMap, String line) {
        Matcher memMatcher = MEM_PATTERN.matcher(line);
        if (memMatcher.matches()) {
            Double memTotal = Double.parseDouble(memMatcher.group(1));
            Double memUsed = Double.parseDouble(memMatcher.group(3));
            metricsMap.put(METRIC_MAP_TOTAL_MEMORY, memTotal);
            metricsMap.put(METRIC_MAP_USED_MEMORY, memUsed);
        }
    }

    private MetricResponse.CpuMetricDTO createCpuMetricDTO(Metric metric, String time) {
        double cpuUsage = Math.round(metric.getCpuUsage() * 1000.0) / 1000.0;
        return new MetricResponse.CpuMetricDTO(time, cpuUsage);
    }

    private MetricResponse.MemoryMetricDTO createMemoryMetricDTO(Metric metric, String time) {
        double memoryUsage = metric.getTotalMemory() > 0 ?
                Math.round((metric.getUsedMemory() / metric.getTotalMemory() * 100) * 1000.0) / 1000.0 : 0.0;
        return new MetricResponse.MemoryMetricDTO(time, memoryUsage);
    }

    private MetricResponse.NetworkInMetricDTO createNetworkInMetricDTO(Metric metric, String time) {
        double networkReceived = Math.round(metric.getTotalBytesReceived() * 1000.0) / 1000.0;
        return new MetricResponse.NetworkInMetricDTO(time, networkReceived);
    }

    private MetricResponse.NetworkOutMetricDTO createNetworkOutMetricDTO(Metric metric, String time) {
        double networkSent = Math.round(metric.getTotalBytesSent() * 1000.0) / 1000.0;
        return new MetricResponse.NetworkOutMetricDTO(time, networkSent);
    }

    private void processMetrics(SshInfo sshInfo, Long monitoringSshId) {
        // 모니터링할 클라우드의 경우 캐시를 업데이트
        boolean updateCache = sshInfo.getId().equals(monitoringSshId);

        Optional<Metric> collectedMetrics = collectMetricsData(sshInfo, updateCache);
        collectedMetrics.ifPresent(metricRepository::save);
    }

    private Optional<Metric> collectMetricsData(SshInfo sshInfo, boolean updateCache) {
        Map<String, Double> topMetrics = collectCpuAndMemoryMetrics(sshInfo.getId());
        Map<String, Double> networkMetrics = collectNetworkMetrics(sshInfo.getId(), updateCache);

        if (topMetrics.isEmpty() || networkMetrics.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(Metric.builder()
                .sshInfo(sshInfo)
                .cpuUsage(topMetrics.get(METRIC_MAP_CPU_USAGE))
                .totalMemory(topMetrics.get(METRIC_MAP_TOTAL_MEMORY))
                .usedMemory(topMetrics.get(METRIC_MAP_USED_MEMORY))
                .totalBytesReceived(networkMetrics.get(METRIC_MAP_NETWORK_REC))
                .totalBytesSent(networkMetrics.get(METRIC_MAP_NETWORK_SENT))
                .build());
    }
}