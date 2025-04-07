package com.example.llmn.domain.metric;

import com.example.llmn.domain.metric.model.response.FindCurrentMetricRes;
import com.example.llmn.domain.metric.model.response.FindMetricHistoryRes;
import com.example.llmn.domain.remote.ServerInstance;
import com.example.llmn.domain.remote.SecureShellManager;
import com.example.llmn.domain.remote.ServerInstanceRepository;
import com.example.llmn.domain.user.UserRepository;
import com.example.llmn.integration.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;

import static com.example.llmn.common.utils.ConverterUtils.convertStringToLong;
import static com.example.llmn.common.utils.DateTimeUtils.getCurrentHourStartMinusHours;
import static com.example.llmn.common.utils.JsonUtils.*;
import static com.example.llmn.domain.metric.MetricConstants.*;
import static com.example.llmn.integration.redis.RedisConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class MetricService {

    private final MetricRepository metricRepository;
    private final UserRepository userRepository;
    private final ServerInstanceRepository serverInstanceRepository;
    private final RedisService redisService;
    private final SecureShellManager secureShellManager;

    public FindCurrentMetricRes findCurrentMetricsForHost(Long serverInstanceId) {
        return retrieveMetricsFromCache(serverInstanceId)
                .orElseGet(() -> {
                    Map<String, Double> cpuAndMemoryStats = gatherCpuAndMemoryData(serverInstanceId);
                    Map<String, Double> networkStats = gatherNetworkData(serverInstanceId);
                    FindCurrentMetricRes metricRes = FindCurrentMetricRes.from(cpuAndMemoryStats, networkStats);

                    storeCurrentMetricsInCache(serverInstanceId, metricRes);
                    return metricRes;
                });
    }

    public FindMetricHistoryRes findHistoricalMetrics(int minusHour, Long serverInstanceId) {
        LocalDateTime startTime = getCurrentHourStartMinusHours(minusHour);
        List<Metric> metrics = metricRepository.findMetricsAfter(startTime, serverInstanceId);

        return FindMetricHistoryRes.from(metrics);
    }

    public List<Metric> gatherMetricsForUsers() {
        return userRepository.findByMonitoringSshIdIsNotNull().stream()
                .flatMap(user -> {
                    Long monitoringSshId = user.getMonitoringSshId();
                    List<ServerInstance> serverInstances = serverInstanceRepository.findByUserId(user.getId());

                    return serverInstances.stream()
                            .filter(serverInstance -> serverInstance.isMonitoringServerInstance(monitoringSshId))
                            .map(serverInstance -> Optional.ofNullable(gatherHostMetrics(serverInstance)))
                            .filter(Optional::isPresent)
                            .map(Optional::get);
                })
                .toList();
    }

    private Metric gatherHostMetrics(ServerInstance serverInstance) {
        Map<String, Double> cpuAndMemoryStats = gatherCpuAndMemoryData(serverInstance.getId());
        Map<String, Double> networkStats = gatherNetworkData(serverInstance.getId());

        if (hasNoValidMetricData(cpuAndMemoryStats, networkStats))
            return null;

        storeNetworkStatsInCache(networkStats);

        return Metric.builder()
                .serverInstance(serverInstance)
                .cpuUsage(cpuAndMemoryStats.get(KEY_CPU_USAGE))
                .totalMemory(cpuAndMemoryStats.get(KEY_TOTAL_MEMORY))
                .usedMemory(cpuAndMemoryStats.get(KEY_USED_MEMORY))
                .totalBytesReceived(networkStats.get(KEY_NETWORK_RECEIVED))
                .totalBytesSent(networkStats.get(KEY_NETWORK_SENT))
                .build();
    }

    private boolean hasNoValidMetricData(Map<String, Double> cpuAndMemoryStats, Map<String, Double> networkStats) {
        return cpuAndMemoryStats.isEmpty() || networkStats.isEmpty();
    }

    private Map<String, Double> gatherCpuAndMemoryData(Long serverInstanceId) {
        String topCommandOutput = secureShellManager.executeOneTimeCommand(CMD_CPU_MEMORY_STATS, serverInstanceId);
        String[] lines = topCommandOutput.split("\n");

        Map<String, Double> statsMap = new HashMap<>();
        for (String line : lines) {
            line = line.trim();
            statsMap.putAll(parseCpuUsage(line));
            statsMap.putAll(parseMemoryUsage(line));
        }

        return statsMap;
    }

    private Map<String, Double> gatherNetworkData(Long serverInstanceId) {
        String networkCommandOutput = secureShellManager.executeOneTimeCommand(CMD_NETWORK_STATS, serverInstanceId);
        String[] lines = networkCommandOutput.split("\\n");

        Map<String, Double> currentNetworkStats = parseNetworkDataIntoMap(lines);

        if (currentNetworkStats.isEmpty())
            return Collections.emptyMap();

        return computeNetworkUsageDelta(currentNetworkStats);
    }

    private Map<String, Double> parseNetworkDataIntoMap(String[] lines) {
        Map<String, Double> networkStatMap = new HashMap<>();
        for (String line : lines) {
            networkStatMap.putAll(parseNetworkUsage(line.trim()));

            if (!networkStatMap.isEmpty()) // 최초로 찾은 유효한 인터페이스만 처리
                break;
        }

        return networkStatMap;
    }

    private Map<String, Double> computeNetworkUsageDelta(Map<String, Double> currentNetworkStats) {
        Double lastReceivedMegabytes = redisService.getValueInDouble(REDIS_KEY_NETWORK_REC);
        Double lastSentMegabytes = redisService.getValueInDouble(REDIS_KEY_NETWORK_TRANS);

        Double megabytesReceivedDelta = currentNetworkStats.getOrDefault(KEY_NETWORK_RECEIVED, DEFAULT_ZERO_VALUE) - lastReceivedMegabytes;
        Double megabytesSentDelta = currentNetworkStats.getOrDefault(KEY_NETWORK_SENT, DEFAULT_ZERO_VALUE) - lastSentMegabytes;

        return Map.of(
                KEY_NETWORK_RECEIVED, megabytesReceivedDelta,
                KEY_NETWORK_SENT, megabytesSentDelta
        );
    }

    private Map<String, Double> parseCpuUsage(String line) {
        Map<String, Double> cpuStatMap = new HashMap<>();

        Matcher cpuMatcher = PATTERN_CPU_LINE.matcher(line);
        if (cpuMatcher.matches()) {
            Double userCpuUsage = Double.parseDouble(cpuMatcher.group(1));
            Double systemCpuUsage = Double.parseDouble(cpuMatcher.group(2));
            Double totalCpuUsage = userCpuUsage + systemCpuUsage;
            cpuStatMap.put(KEY_CPU_USAGE, totalCpuUsage);
        }

        return cpuStatMap;
    }

    private Map<String, Double> parseMemoryUsage(String line) {
        Map<String, Double> memoryStatMap = new HashMap<>();

        Matcher memMatcher = PATTERN_MEMORY_LINE.matcher(line);
        if (memMatcher.matches()) {
            Double totalMemoryMB = Double.parseDouble(memMatcher.group(1));
            Double usedMemoryMB = Double.parseDouble(memMatcher.group(3));
            memoryStatMap.put(KEY_TOTAL_MEMORY, totalMemoryMB);
            memoryStatMap.put(KEY_USED_MEMORY, usedMemoryMB);
        }

        return memoryStatMap;
    }

    private Map<String, Double> parseNetworkUsage(String line) {
        Map<String, Double> networkUsageMap = new HashMap<>();

        Matcher netMatcher = PATTERN_NETWORK_INTERFACE.matcher(line);
        if (netMatcher.find()) {
            String[] parts = line.split("\\s+");

            if (parts.length >= 10) {
                long receivedBytes = convertStringToLong(parts[NETWORK_RECEIVED_BYTES_INDEX]);
                long transmittedBytes = convertStringToLong(parts[NETWORK_SENT_BYTES_INDEX]);
                networkUsageMap.put(KEY_NETWORK_RECEIVED, bytesToMegabytes(receivedBytes));
                networkUsageMap.put(KEY_NETWORK_SENT, bytesToMegabytes(transmittedBytes));
            }
        }
        return networkUsageMap;
    }

    private Optional<FindCurrentMetricRes> retrieveMetricsFromCache(Long serverInstanceId) {
        String cachedMetricJson = redisService.getValueInString(REDIS_KEY_METRIC, serverInstanceId.toString());
        if (cachedMetricJson == null)
            return Optional.empty();

        return Optional.ofNullable(convertJsonToMetricDTO(cachedMetricJson));
    }

    private void storeCurrentMetricsInCache(Long serverInstanceId, FindCurrentMetricRes metricRes) {
        String value = convertMetricDtoToJson(metricRes);
        if (isNotEmpty(value))
            redisService.storeValue(REDIS_KEY_METRIC, serverInstanceId.toString(), value, REDIS_EXP_METRIC_MS);
    }

    private void storeNetworkStatsInCache(Map<String, Double> currentNetworkMetric) {
        redisService.storeValue(REDIS_KEY_NETWORK_REC, String.valueOf(currentNetworkMetric.get(KEY_NETWORK_RECEIVED)));
        redisService.storeValue(REDIS_KEY_NETWORK_TRANS, String.valueOf(currentNetworkMetric.get(KEY_NETWORK_SENT)));
    }

    private double bytesToMegabytes(long bytes) {
        return bytes / BYTES_TO_MB_DIVISOR;
    }
}