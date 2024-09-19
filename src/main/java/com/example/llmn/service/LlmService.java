package com.example.llmn.service;

import com.example.llmn.controller.DTO.LogDTO;
import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.domain.ContainerStatus;
import com.example.llmn.domain.Project;
import com.example.llmn.domain.Summary;
import com.example.llmn.domain.SummaryType;
import com.example.llmn.repository.ProjectRepository;
import com.example.llmn.repository.SummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LlmService {

    private final LogService logService;
    private final MetricService metricService;
    private final SummaryRepository summaryRepository;
    private final ProjectRepository projectRepository;
    private final LogWebSocketHandler logWebSocketHandler;
    private final WebClient webClient;
    private static final String LOG_SUMMERY_URI = "http://localhost:8000/process/logSummary";
    private static final String PERFORMANCE_SUMMERY_URI = "http://localhost:8000/process/performanceSummary";
    private static final int METRIC_HISTORY_PREVIOUS_HOUR = 1;

    // 주기적으로 로그를 가져와 특정 서비스의 로그만 전송
    @Scheduled(fixedRate = 50000)
    public void streamLogs() {
        String serviceName = "spring";
        String logMessage = "Spring 서비스의 로그 메시지";

        // WebSocket을 통해 서비스 로그 전송
        logWebSocketHandler.sendLog(serviceName, logMessage);
    }

    @Transactional
    @Scheduled(cron = "0 0,30 * * * *")
    public void summaryProjectLog(){
        List<Project> projects = projectRepository.findAll();

        Instant endTime = Instant.now();
        Instant startTime = endTime.minus(30, ChronoUnit.MINUTES);

        projects.stream()
                .filter(project -> !project.getContainerStatus().equals(ContainerStatus.NOT_CONNECTED))
                .forEach(project -> {
                    LogDTO.SummaryResponseDTO summaryDTO = fetchLogSummary(startTime, endTime, project.getContainerName());

                    if(summaryDTO == null){
                        return;
                    }

                    // 일반 요약 저장
                    Summary generalSummary = Summary.builder()
                            .project(project)
                            .content(summaryDTO.generalSummary())
                            .summaryType(SummaryType.GENERAL)
                            .build();

                    summaryRepository.save(generalSummary);

                    // 비정상 패턴 요약 저장
                    Summary anomalySummary = Summary.builder()
                            .project(project)
                            .content(summaryDTO.anomalySummary())
                            .summaryType(SummaryType.ANOMALY)
                            .build();

                    summaryRepository.save(anomalySummary);
                });
    }

    @Transactional
    @Scheduled(cron = "0 15,45 * * * *")
    public void summaryPerformance(){
        LogDTO.PerformanceSummaryResponseDTO performanceSummaryDTO = fetchMetricSummary();

        Summary performanceSummary = Summary.builder()
                .content(performanceSummaryDTO.performanceSummary())
                .summaryType(SummaryType.PERFORMANCE)
                .build();

        summaryRepository.save(performanceSummary);
    }

    private LogDTO.SummaryResponseDTO fetchLogSummary(Instant startTime, Instant endTime, String serviceName) {
        // 로그 메시지는 ElasticSearch에서 가져온다
        String logMessage = logService.searchLogInStr(startTime, endTime, serviceName);

        // 검색 결과가 빈 값이면 null을 반환
        if (logMessage == null || logMessage.isEmpty()) {
            return null;
        }

        return webClient.post()
                .uri(buildURI(LOG_SUMMERY_URI))
                .bodyValue(new LogDTO.SummaryRequestDTO(logMessage))
                .retrieve()
                .bodyToMono(LogDTO.SummaryResponseDTO.class)
                .block();
    }

    private LogDTO.PerformanceSummaryResponseDTO fetchMetricSummary(){
        // 1시간 전까지의 성능 지표
        MetricResponse.FindMetricHistoryDTO metricHistory = metricService.findMetricHistory(METRIC_HISTORY_PREVIOUS_HOUR);

        // StringBuilder를 사용하여 메트릭 정보를 문자열로 변환
        StringBuilder logMessageBuilder = new StringBuilder();

        // CPU 메트릭 정보를 추가
        logMessageBuilder.append("CPU Metrics:\n");
        for (MetricResponse.CpuMetricDTO cpuMetric : metricHistory.cpuMetrics()) {
            logMessageBuilder.append(String.format("- Time: %s, CPU Usage: %.2f%%\n", cpuMetric.time(), cpuMetric.cpuUsage()));
        }

        // 메모리 메트릭 정보를 추가
        logMessageBuilder.append("\nMemory Metrics:\n");
        for (MetricResponse.MemoryMetricDTO memoryMetric : metricHistory.memoryMetrics()) {
            logMessageBuilder.append(String.format("- Time: %s, Memory Usage: %d MB\n", memoryMetric.time(), memoryMetric.memoryUsage()));
        }

        // 네트워크 In 메트릭 정보를 추가
        logMessageBuilder.append("\nNetwork In Metrics:\n");
        for (MetricResponse.NetworkInMetricDTO networkInMetric : metricHistory.networkInMetrics()) {
            logMessageBuilder.append(String.format("- Time: %s, Network Received: %.2f MB\n", networkInMetric.time(), networkInMetric.networkReceived()));
        }

        // 네트워크 Out 메트릭 정보를 추가
        logMessageBuilder.append("\nNetwork Out Metrics:\n");
        for (MetricResponse.NetworkOutMetricDTO networkOutMetric : metricHistory.networkOutMetrics()) {
            logMessageBuilder.append(String.format("- Time: %s, Network Sent: %.2f MB\n", networkOutMetric.time(), networkOutMetric.networkSent()));
        }

        // logMessage 문자열로 변환
        String logMessage = logMessageBuilder.toString();

        return webClient.post()
                .uri(buildURI(PERFORMANCE_SUMMERY_URI))
                .bodyValue(new LogDTO.SummaryRequestDTO(logMessage))
                .retrieve()
                .bodyToMono(LogDTO.PerformanceSummaryResponseDTO.class)
                .block();
    }

    private URI buildURI(String uri) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(uri);
        return uriBuilder.build().encode().toUri();
    }
}
