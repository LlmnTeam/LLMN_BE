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

        if (hasNoValidMetricData(cpuAndMemoryStats, networkStats)) return null;
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
        Double totalMemoryMB = null;

        for (String line : lines) {
            line = line.trim();

            if (line.startsWith("%Cpu")) statsMap.putAll(parseCpuUsage(line));
            else if (line.startsWith("MiB Mem")) totalMemoryMB = parseMemLine(line, statsMap);
            else if (line.startsWith("MiB Swap")) parseSwapLine(line, statsMap, totalMemoryMB);
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

            // 최초로 찾은 유효한 인터페이스만 처리
            if (!networkStatMap.isEmpty()) break;
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
            Double niceCpuUsage = Double.parseDouble(cpuMatcher.group(3));
            Double idleCpuUsage = Double.parseDouble(cpuMatcher.group(4));
            Double iowaitCpuUsage = Double.parseDouble(cpuMatcher.group(5));
            Double hiCpuUsage = Double.parseDouble(cpuMatcher.group(6));
            Double siCpuUsage = Double.parseDouble(cpuMatcher.group(7));
            Double stealCpuUsage = Double.parseDouble(cpuMatcher.group(8));

            cpuStatMap.put(KEY_CPU_USER, userCpuUsage);
            cpuStatMap.put(KEY_CPU_SYSTEM, systemCpuUsage);
            cpuStatMap.put(KEY_CPU_NICE, niceCpuUsage);
            cpuStatMap.put(KEY_CPU_IDLE, idleCpuUsage);
            cpuStatMap.put(KEY_CPU_IOWAIT, iowaitCpuUsage);
            cpuStatMap.put(KEY_CPU_HI, hiCpuUsage);
            cpuStatMap.put(KEY_CPU_SI, siCpuUsage);
            cpuStatMap.put(KEY_CPU_STEAL, stealCpuUsage);

            // 총 CPU 사용량 계산 (idle을 제외한 모든 상태의 합)
            Double totalCpuUsage = 100.0 - idleCpuUsage;
            cpuStatMap.put(KEY_CPU_USAGE, totalCpuUsage);
        }

        return cpuStatMap;
    }

    private Double parseMemLine(String line, Map<String, Double> statsMap) {
        Matcher memMatcher = PATTERN_MEM_LINE.matcher(line);
        if (memMatcher.matches()) {
            Double totalMemoryMB = Double.parseDouble(memMatcher.group(1)); // 총 메모리
            Double freeMemoryMB = Double.parseDouble(memMatcher.group(2)); // 사용되지 않은 메모리
            Double usedMemoryMB = Double.parseDouble(memMatcher.group(3)); // 초기 사용 메모리 값
            Double buffCacheMemoryMB = Double.parseDouble(memMatcher.group(4)); // 버퍼, 캐시

            statsMap.put(KEY_TOTAL_MEMORY, totalMemoryMB);
            statsMap.put(KEY_FREE_MEMORY, freeMemoryMB);
            statsMap.put(KEY_USED_MEMORY, usedMemoryMB);
            statsMap.put(KEY_BUFFCACHE_MEMORY, buffCacheMemoryMB);

            return totalMemoryMB;
        }
        return null;
    }

    private void parseSwapLine(String line, Map<String, Double> statsMap, Double totalMemoryMB) {
        Matcher swapMatcher = PATTERN_SWAP_LINE.matcher(line);
        if (swapMatcher.matches()) {
            Double availableMemoryMB = Double.parseDouble(swapMatcher.group(1)); // 실제 사용 가능한 메모리
            statsMap.put(KEY_AVAILABLE_MEMORY, availableMemoryMB);

            if (totalMemoryMB != null) {
                // 실제 사용 중인 메모리 = 총 메모리 - 사용 가능한 메모리
                Double actualUsedMemory = totalMemoryMB - availableMemoryMB;

                // 메모리 사용률(%) = (실제 사용 메모리 / 총 메모리) * 100
                Double usagePercent = (actualUsedMemory / totalMemoryMB) * 100.0;

                statsMap.put(KEY_MEMORY_USAGE_PERCENT, usagePercent);
                statsMap.put(KEY_USED_MEMORY, actualUsedMemory); // 초기 사용 메모리 값을 실제 사용 메모리로 업데이트
            }
        }
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