package com.example.llmn.domain.metric;

import com.example.llmn.domain.metric.model.response.FindCurrentMetricRes;
import com.example.llmn.domain.metric.model.response.FindMetricHistoryRes;
import com.example.llmn.domain.ssh.SshInfo;
import com.example.llmn.domain.user.User;
import com.example.llmn.domain.ssh.SSHService;
import com.example.llmn.domain.ssh.SshInfoRepository;
import com.example.llmn.domain.user.UserRepository;
import com.example.llmn.integration.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;

import static com.example.llmn.common.utils.ConverterUtils.convertStringToLong;
import static com.example.llmn.common.utils.DateTimeUtils.getCurrentHourStartMinusHours;
import static com.example.llmn.common.utils.JsonUtils.*;
import static com.example.llmn.domain.metric.MetricConstants.*;
import static com.example.llmn.integration.redis.RedisConstants.REDIS_KEY_NETWORK_REC;
import static com.example.llmn.integration.redis.RedisConstants.REDIS_KEY_NETWORK_TRANS;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class MetricService {

    private final MetricRepository metricRepository;
    private final UserRepository userRepository;
    private final SshInfoRepository sshInfoRepository;
    private final RedisService redisService;
    private final SSHService sshService;

    private static final String METRIC_KEY = "metric";
    private static final Long METRIC_EXP = 10 * 60 * 1000L; // 10분
    private static final double DEFAULT_METRIC_VALUE = 0.0;
    private static final double BYTES_TO_MB_DIVISOR = 1024.0 * 1024.0;
    private static final int RECEIVED_BYTES_INDEX = 1;
    private static final int TRANSMITTED_BYTES_INDEX = 9;

    @Scheduled(cron = "0 0/10 * * * *")
    @Transactional
    public void collectMetrics() {
        List<User> users = userRepository.findByMonitoringSshIdIsNotNull();

        List<Metric> allMetrics = collectAllMetrics(users);
        metricRepository.saveAll(allMetrics);
    }

    public FindCurrentMetricRes findCurrentMetric(Long sshInfoId) {
        return retrieveCachedMetric(sshInfoId)
                .orElseGet(() -> {
                    Map<String, Double> cpuAndMemoryMetrics = collectCpuAndMemoryMetrics(sshInfoId);
                    Map<String, Double> networkMetrics = collectNetworkMetrics(sshInfoId);
                    FindCurrentMetricRes metricRes = FindCurrentMetricRes.from(cpuAndMemoryMetrics, networkMetrics);

                    cacheMetric(sshInfoId, metricRes);
                    return metricRes;
                });
    }

    public FindMetricHistoryRes findMetricHistory(int minusHour, Long sshInfoId) {
        LocalDateTime startTime = getCurrentHourStartMinusHours(minusHour);
        List<Metric> metrics = metricRepository.findMetricsAfter(startTime, sshInfoId);

        return FindMetricHistoryRes.from(metrics);
    }

    private List<Metric> collectAllMetrics(List<User> users) {
        return users.stream()
                .flatMap(user -> collectMetricsForUser(user).stream())
                .toList();
    }

    private List<Metric> collectMetricsForUser(User user) {
        Long monitoringSshId = user.getMonitoringSshId();
        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(user.getId());

        return sshInfos.stream()
                .filter(sshInfo -> sshInfo.isMonitoringSsh(monitoringSshId))
                .map(sshInfo -> Optional.ofNullable(collectMetricsFromSsh(sshInfo)))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    private Metric collectMetricsFromSsh(SshInfo sshInfo) {
        Map<String, Double> cpuAndMemoryMetrics = collectCpuAndMemoryMetrics(sshInfo.getId());
        Map<String, Double> networkMetrics = collectNetworkMetrics(sshInfo.getId());

        if (isMetricEmpty(cpuAndMemoryMetrics, networkMetrics)) {
            return null;
        }
        cacheNetworkMetric(networkMetrics);

        return Metric.builder()
                .sshInfo(sshInfo)
                .cpuUsage(cpuAndMemoryMetrics.get(METRIC_MAP_CPU_USAGE))
                .totalMemory(cpuAndMemoryMetrics.get(METRIC_MAP_TOTAL_MEMORY))
                .usedMemory(cpuAndMemoryMetrics.get(METRIC_MAP_USED_MEMORY))
                .totalBytesReceived(networkMetrics.get(METRIC_MAP_NETWORK_RECEIVED))
                .totalBytesSent(networkMetrics.get(METRIC_MAP_NETWORK_SENT))
                .build();
    }

    private boolean isMetricEmpty(Map<String, Double> cpuAndMemoryMetrics, Map<String, Double> networkMetrics) {
        return cpuAndMemoryMetrics.isEmpty() || networkMetrics.isEmpty();
    }

    private Map<String, Double> collectCpuAndMemoryMetrics(Long sshInfoId) {
        String commandResponse = sshService.executeCommandOnce(COMMAND_TOP, sshInfoId);
        String[] lines = commandResponse.split("\n");

        Map<String, Double> metricsMap = new HashMap<>();
        for (String line : lines) {
            line = line.trim();
            metricsMap.putAll(parseCpuUsage(line));
            metricsMap.putAll(parseMemoryUsage(line));
        }

        return metricsMap;
    }

    private Map<String, Double> collectNetworkMetrics(Long sshInfoId) {
        Map<String, Double> currentNetworkMetric = collectCurrentNetworkMetrics(sshInfoId);

        if (currentNetworkMetric.isEmpty()) {
            return Collections.emptyMap();
        }

        return calculateNetworkUsage(currentNetworkMetric);
    }

    private Map<String, Double> collectCurrentNetworkMetrics(Long sshInfoId) {
        String commandResponse = sshService.executeCommandOnce(COMMAND_NETWORK_USAGE, sshInfoId);
        String[] lines = commandResponse.split("\\n");

        return createNetworkMetricMap(lines);
    }

    private Map<String, Double> createNetworkMetricMap(String[] lines) {
        Map<String, Double> networkMetricMap = new HashMap<>();
        for (String line : lines) {
            networkMetricMap.putAll(parseNetworkUsage(line.trim()));
            if (!networkMetricMap.isEmpty()) break; // 최초로 찾은 유효한 인터페이스만 처리
        }

        return networkMetricMap;
    }

    private Map<String, Long> findTodayNetworkMetrics(Long sshInfoId) {
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<Metric> networkMetrics = metricRepository.findMetricsAfter(todayStart, sshInfoId);

        return calculateTotalNetworkUsage(networkMetrics);
    }

    private Map<String, Double> calculateNetworkUsage(Map<String, Double> currentNetworkMetrics) {
        Double previousReceivedMB = redisService.getValueInDouble(REDIS_KEY_NETWORK_REC);
        Double previousTransmittedMB = redisService.getValueInDouble(REDIS_KEY_NETWORK_TRANS);

        Double receivedDifference = currentNetworkMetrics.getOrDefault(METRIC_MAP_NETWORK_RECEIVED, DEFAULT_METRIC_VALUE) - previousReceivedMB;
        Double transmittedDifference = currentNetworkMetrics.getOrDefault(METRIC_MAP_NETWORK_SENT, DEFAULT_METRIC_VALUE) - previousTransmittedMB;

        return Map.of(
                METRIC_MAP_NETWORK_RECEIVED, receivedDifference,
                METRIC_MAP_NETWORK_SENT, transmittedDifference
        );
    }

    private Map<String, Long> calculateTotalNetworkUsage(List<Metric> metrics) {
        Long totalReceived = metrics.stream()
                .mapToLong(metric -> Math.round(metric.getTotalBytesReceived()))
                .sum();

        Long totalSent = metrics.stream()
                .mapToLong(metric -> Math.round(metric.getTotalBytesSent()))
                .sum();

        return Map.of(
                METRIC_MAP_DAILY_NET_RECEIVED, totalReceived,
                METRIC_MAP_DAILY_NET_SENT, totalSent
        );
    }

    private Optional<FindCurrentMetricRes> retrieveCachedMetric(Long sshInfoId) {
        String cachedValue = redisService.getValueInString(METRIC_KEY, sshInfoId.toString());
        if (cachedValue == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(convertJsonToMetricDTO(cachedValue));
    }

    private void cacheMetric(Long sshInfoId, FindCurrentMetricRes metricRes) {
        String value = convertMetricDtoToJson(metricRes);

        if (isNotEmpty(value)) {
            redisService.storeValue(METRIC_KEY, sshInfoId.toString(), value, METRIC_EXP);
        }
    }

    private void cacheNetworkMetric(Map<String, Double> currentNetworkMetric) {
        redisService.storeValue(REDIS_KEY_NETWORK_REC, String.valueOf(currentNetworkMetric.get(METRIC_MAP_NETWORK_RECEIVED)));
        redisService.storeValue(REDIS_KEY_NETWORK_TRANS, String.valueOf(currentNetworkMetric.get(METRIC_MAP_NETWORK_SENT)));
    }

    private Map<String, Double> parseCpuUsage(String line) {
        Map<String, Double> cpuMetrics = new HashMap<>();

        Matcher cpuMatcher = CPU_PATTERN.matcher(line);
        if (cpuMatcher.matches()) {
            Double usUsage = Double.parseDouble(cpuMatcher.group(1));
            Double syUsage = Double.parseDouble(cpuMatcher.group(2));
            Double cpuUsage = usUsage + syUsage;
            cpuMetrics.put(METRIC_MAP_CPU_USAGE, cpuUsage);
        }

        return cpuMetrics;
    }

    private Map<String, Double> parseMemoryUsage(String line) {
        Map<String, Double> memoryMetrics = new HashMap<>();

        Matcher memMatcher = MEM_PATTERN.matcher(line);
        if (memMatcher.matches()) {
            Double memTotal = Double.parseDouble(memMatcher.group(1));
            Double memUsed = Double.parseDouble(memMatcher.group(3));
            memoryMetrics.put(METRIC_MAP_TOTAL_MEMORY, memTotal);
            memoryMetrics.put(METRIC_MAP_USED_MEMORY, memUsed);
        }

        return memoryMetrics;
    }

    private Map<String, Double> parseNetworkUsage(String line) {
        Map<String, Double> networkUsageMap = new HashMap<>();

        Matcher matcher = NETWORK_PATTERN.matcher(line);
        if (matcher.find()) {
            String[] parts = line.split("\\s+");

            if (parts.length >= 10) {
                long receivedBytes = convertStringToLong(parts[RECEIVED_BYTES_INDEX]);
                long transmittedBytes = convertStringToLong(parts[TRANSMITTED_BYTES_INDEX]);
                networkUsageMap.put(METRIC_MAP_NETWORK_RECEIVED, convertBytesToMB(receivedBytes));
                networkUsageMap.put(METRIC_MAP_NETWORK_SENT, convertBytesToMB(transmittedBytes));
            }
        }

        return networkUsageMap;
    }

    private double convertBytesToMB(long bytes) {
        return bytes / BYTES_TO_MB_DIVISOR;
    }
}