package com.example.llmn.service;

import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.controller.DTO.UserResponse;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.core.utils.DateTimeUtils;
import com.example.llmn.domain.Metric;

import com.example.llmn.domain.SshInfo;
import com.example.llmn.domain.SummaryType;
import com.example.llmn.domain.User;
import com.example.llmn.repository.MetricRepository;
import com.example.llmn.repository.SshInfoRepository;
import com.example.llmn.repository.SummaryRepository;
import com.example.llmn.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.llmn.core.utils.ConverterUtils.convertStringToLong;
import static com.example.llmn.core.utils.DateTimeUtils.*;
import static com.example.llmn.core.utils.JsonUtils.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class MetricService {

    private final MetricRepository metricRepository;
    private final UserRepository userRepository;
    private final SshInfoRepository sshInfoRepository;
    private final RedisService redisService;
    private final SSHService sshService;

    private static final String REDIS_KEY_NETWORK_REC = "network:received";
    private static final String REDIS_KEY_NETWORK_TRANS = "network:transmitted";
    private static final String METRIC_KEY = "metric";
    private static final Long METRIC_EXP = 10 * 60 * 1000L; // 10분
    private static final String METRIC_MAP_CPU_USAGE = "cpuUsage";
    private static final String METRIC_MAP_TOTAL_MEMORY = "totalMemory";
    private static final String METRIC_MAP_USED_MEMORY = "usedMemory";
    private static final String METRIC_MAP_NETWORK_RECEIVED = "networkReceived";
    private static final String METRIC_MAP_NETWORK_SENT ="networkSent";
    private static final String METRIC_MAP_DAILY_NET_RECEIVED = "dailyReceived";
    private static final String METRIC_MAP_DAILY_NET_SENT ="dailySent";
    private static final String COMMAND_TOP = "top -b -n1 | grep \"Cpu(s)\\|Mem\"";
    private static final String COMMAND_NETWORK_USAGE = "cat /proc/net/dev";
    private static final double DEFAULT_METRIC_VALUE = 0.0;
    private static final double BYTES_TO_MB_DIVISOR = 1024.0 * 1024.0;
    private static final int RECEIVED_BYTES_INDEX = 1;
    private static final int TRANSMITTED_BYTES_INDEX = 9;
    private static final Pattern CPU_PATTERN = Pattern.compile("%Cpu\\(s\\):\\s+([\\d.]+)\\s+us,\\s+([\\d.]+)\\s+sy,.*");
    private static final Pattern MEM_PATTERN = Pattern.compile("MiB Mem :\\s+([\\d.]+)\\s+total,\\s+([\\d.]+)\\s+free,\\s+([\\d.]+)\\s+used,.*");
    private static final Pattern NETWORK_PATTERN = Pattern.compile("^(eth|ens|enp|wlan)\\S*:"); // 주요 네트워크 인터페이스 패턴

    @Scheduled(cron = "0 0/10 * * * *")
    @Transactional
    public void collectMetrics() {
        List<User> users = userRepository.findByMonitoringSshIdIsNotNull();

        List<Metric> allMetrics = collectAllMetrics(users);
        metricRepository.saveAll(allMetrics);
    }

    public MetricResponse.FindCurrentMetricDTO findCurrentMetric(Long sshInfoId) {
        return retrieveCachedMetric(sshInfoId)
                .orElseGet(() -> {
                    Map<String, Double> cpuAndMemoryMetrics = collectCpuAndMemoryMetrics(sshInfoId);
                    Map<String, Double> networkMetrics = collectNetworkMetrics(sshInfoId);
                    MetricResponse.FindCurrentMetricDTO metricDTO = createFindCurrentMetricDTO(cpuAndMemoryMetrics, networkMetrics);

                    cacheMetric(sshInfoId, metricDTO);
                    return metricDTO;
                });
    }

    @Transactional(readOnly = true)
    public MetricResponse.FindMetricHistoryDTO findMetricHistory(int minusHour, Long sshInfoId){
        List<MetricResponse.CpuMetricDTO> cpuMetricDTOS = new ArrayList<>();
        List<MetricResponse.MemoryMetricDTO> memoryMetricDTOS = new ArrayList<>();
        List<MetricResponse.NetworkInMetricDTO> networkInMetricDTOS = new ArrayList<>();
        List<MetricResponse.NetworkOutMetricDTO> networkOutMetricDTOS = new ArrayList<>();

        LocalDateTime startTime = getCurrentHourStartMinusHours(minusHour);
        List<Metric> metrics = metricRepository.findMetricsAfter(startTime, sshInfoId);

        metrics.forEach(metric -> {
            String time = DateTimeUtils.formatLocalDateTime(metric.getCreatedDate(), HOUR_MINUTE_FORMATTER);
            cpuMetricDTOS.add(createCpuMetricDTO(metric, time));
            memoryMetricDTOS.add(createMemoryMetricDTO(metric, time));
            networkInMetricDTOS.add(createNetworkInMetricDTO(metric, time));
            networkOutMetricDTOS.add(createNetworkOutMetricDTO(metric, time));
        });

        return new MetricResponse.FindMetricHistoryDTO(cpuMetricDTOS, memoryMetricDTOS, networkInMetricDTOS, networkOutMetricDTOS);
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

        if(currentNetworkMetric.isEmpty()){
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

    private Optional<MetricResponse.FindCurrentMetricDTO> retrieveCachedMetric(Long sshInfoId) {
        String cachedValue = redisService.getValueInString(METRIC_KEY, sshInfoId.toString());
        if (cachedValue == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(convertJsonToMetricDTO(cachedValue));
    }

    private void cacheMetric(Long sshInfoId, MetricResponse.FindCurrentMetricDTO metricDTO) {
        String value = convertMetricDtoToJson(metricDTO);

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

    private double convertBytesToMB(long bytes) {
        return bytes / BYTES_TO_MB_DIVISOR;
    }

    private  MetricResponse.FindCurrentMetricDTO createFindCurrentMetricDTO(Map<String, Double> cpuAndMemoryMetrics, Map<String, Double> networkMetrics){
        return new MetricResponse.FindCurrentMetricDTO(
                cpuAndMemoryMetrics.getOrDefault(METRIC_MAP_CPU_USAGE, DEFAULT_METRIC_VALUE),
                cpuAndMemoryMetrics.getOrDefault(METRIC_MAP_TOTAL_MEMORY, DEFAULT_METRIC_VALUE),
                cpuAndMemoryMetrics.getOrDefault(METRIC_MAP_USED_MEMORY, DEFAULT_METRIC_VALUE),
                networkMetrics.getOrDefault(METRIC_MAP_NETWORK_RECEIVED, DEFAULT_METRIC_VALUE),
                networkMetrics.getOrDefault(METRIC_MAP_NETWORK_SENT, DEFAULT_METRIC_VALUE)
        );
    }
}