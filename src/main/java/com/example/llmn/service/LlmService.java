package com.example.llmn.service;

import com.example.llmn.controller.DTO.LogDTO;
import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.domain.ContainerStatus;
import com.example.llmn.domain.Project;
import com.example.llmn.domain.Summary;
import com.example.llmn.domain.SummaryType;
import com.example.llmn.repository.ProjectRepository;
import com.example.llmn.repository.SummaryRepository;
import com.example.llmn.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LlmService {

    private final LogService logService;
    private final MetricService metricService;
    private final UserRepository userRepository;
    private final SummaryRepository summaryRepository;
    private final ProjectRepository projectRepository;
    private final WebClient webClient;
    private static final String LOG_SUMMERY_URI = "http://localhost:8000/process/logSummary";
    private static final String PERFORMANCE_SUMMERY_URI = "http://localhost:8000/process/performanceSummary";
    private static final String DAILY_SUMMERY_URI = "http://localhost:8000/process/dailySummary";
    private static final String HOURLY_SUMMARY_URI = "http://localhost:8000/process/hourlySummary";
    private static final String TREND_SUMMERY_URI = "http://localhost:8000/process/trendSummary";
    private static final String RECOMMEND_URI = "http://localhost:8000/process/recommend";
    private static final int METRIC_HISTORY_PREVIOUS_HOUR = 1;

    @Transactional
    @Scheduled(cron = "0 0 * * * *") // 매시 0분
    public void summaryProjectLog(){
        List<Project> projects = projectRepository.findAll().stream()
                .filter(project -> !project.getContainerStatus().equals(ContainerStatus.NOT_CONNECTED))
                .toList();

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
    @Scheduled(cron = "0 5 * * * *") // 매시 5분에
    public void summaryPerformance(){
        List<Long> userIds = userRepository.findIds();

        for(Long userId: userIds) {
            LogDTO.PerformanceSummaryResponseDTO performanceSummaryDTO = fetchMetricSummary(userId);

            Summary performanceSummary = Summary.builder()
                    .content(performanceSummaryDTO.performanceSummary())
                    .summaryType(SummaryType.PERFORMANCE)
                    .build();

            summaryRepository.save(performanceSummary);
        }
    }

    @Transactional
    @Scheduled(cron = "0 10 * * * *") // 매시 10분에
    public void summaryHourly(){
        LogDTO.HourlySummaryResponseDTO hourlySummaryDTO = fetchHourlySummary();

        Summary hourlySummary = Summary.builder()
                .content(hourlySummaryDTO.hourlySummary())
                .summaryType(SummaryType.HOURLY)
                .build();

        summaryRepository.save(hourlySummary);
    }

    @Transactional
    @Scheduled(cron = "0 55 23 * * *") // 매일 11시 55분
    public void summaryDaily(){
        LogDTO.DailySummaryResponseDTO dailySummaryDTO = fetchDailySummary();

        Summary dailySummary = Summary.builder()
                .content(dailySummaryDTO.dailySummary())
                .summaryType(SummaryType.DAILY)
                .build();

        summaryRepository.save(dailySummary);
    }

    @Transactional
    @Scheduled(cron = "0 45 23 * * 0") // 매주 일요일 11시 45분
    public void summaryTrend(){
        LogDTO.TrendSummaryResponseDTO trendSummaryDTO = fetchTrendSummary();

        Summary trendSummary = Summary.builder()
                .content(trendSummaryDTO.trendSummary())
                .summaryType(SummaryType.TEND)
                .build();

        summaryRepository.save(trendSummary);
    }

    @Transactional
    @Scheduled(cron = "0 20 0,6,12,18 * * *") // 6시간 간격으로 20분에
    public void recommend(){
        LogDTO.RecommendationDTO recommendationDTO = fetchRecommendation();

        Summary recommend = Summary.builder()
                .content(recommendationDTO.recommend())
                .summaryType(SummaryType.RECOMMENDATION)
                .build();

        summaryRepository.save(recommend);
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

    private LogDTO.PerformanceSummaryResponseDTO fetchMetricSummary(Long userId){
        // 1시간 전까지의 성능 지표
        MetricResponse.FindMetricHistoryDTO metricHistory = metricService.findMetricHistory(METRIC_HISTORY_PREVIOUS_HOUR, userId);

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
            logMessageBuilder.append(String.format("- Time: %s, Memory Usage: %.2f MB\n", memoryMetric.time(), memoryMetric.memoryUsage()));
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

    private LogDTO.HourlySummaryResponseDTO fetchHourlySummary() {
        StringBuilder logMessageBuilder = new StringBuilder();

        LocalDateTime startOfHour = LocalDateTime.now().withMinute(0).minusSeconds(0);

        // 성능 요약 리스트와 어플리케이션 요약 리스트
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), startOfHour);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.GENERAL, SummaryType.ANOMALY), startOfHour);

        // 성능 요약을 로그 메시지로 변환
        logMessageBuilder.append("### Performance Summary ###\n");
        if (performanceSummaries.isEmpty()) {
            logMessageBuilder.append("- 최근에 성능 요약이 없습니다.\n");
        } else {
            for (Summary summary : performanceSummaries) {
                logMessageBuilder.append("Time: ")
                        .append(summary.getCreatedDate())
                        .append("\n")
                        .append(summary.getContent())
                        .append("\n\n");
            }
        }

        // 일반 및 이상 로그 요약을 로그 메시지로 변환
        logMessageBuilder.append("\n### Application Log Summary ###\n");
        if (logSummaries.isEmpty()) {
            logMessageBuilder.append("- 최근에 로그 요약이 없습니다.\n");
        } else {
            for (Summary summary : logSummaries) {
                logMessageBuilder.append("Time: ")
                        .append(summary.getCreatedDate())
                        .append("\n")
                        .append(summary.getContent())
                        .append("\n\n");
            }
        }

        String logMessage = logMessageBuilder.toString();

        return webClient.post()
                .uri(buildURI(HOURLY_SUMMARY_URI))
                .bodyValue(new LogDTO.SummaryRequestDTO(logMessage))
                .retrieve()
                .bodyToMono(LogDTO.HourlySummaryResponseDTO.class)
                .block();
    }

    private LogDTO.DailySummaryResponseDTO fetchDailySummary() {
        StringBuilder logMessageBuilder = new StringBuilder();

        LocalDateTime startOfDay = LocalDateTime.now().with(LocalTime.MIN);

        // 성능 요약 리스트와 어플리케이션 요약 리스트
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), startOfDay);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.GENERAL, SummaryType.ANOMALY), startOfDay);

        // 성능 요약을 로그 메시지로 변환
        logMessageBuilder.append("### Performance Summary ###\n");
        if (performanceSummaries.isEmpty()) {
            logMessageBuilder.append("- 오늘의 성능 요약이 없습니다.\n");
        } else {
            for (Summary summary : performanceSummaries) {
                logMessageBuilder.append("Time: ")
                        .append(summary.getCreatedDate())
                        .append("\n")
                        .append(summary.getContent())
                        .append("\n\n");
            }
        }

        // 일반 및 이상 로그 요약을 로그 메시지로 변환
        logMessageBuilder.append("\n### Application Log Summary ###\n");
        if (logSummaries.isEmpty()) {
            logMessageBuilder.append("- 오늘의 로그 요약이 없습니다.\n");
        } else {
            for (Summary summary : logSummaries) {
                logMessageBuilder.append("Time: ")
                        .append(summary.getCreatedDate())
                        .append("\n")
                        .append(summary.getContent())
                        .append("\n\n");
            }
        }

        // logMessage 문자열로 변환
        String logMessage = logMessageBuilder.toString();

        // LLM에 전달하기 위해 FastAPI에 요청
        return webClient.post()
                .uri(buildURI(DAILY_SUMMERY_URI))
                .bodyValue(new LogDTO.SummaryRequestDTO(logMessage))
                .retrieve()
                .bodyToMono(LogDTO.DailySummaryResponseDTO.class)
                .block();
    }

    private LogDTO.TrendSummaryResponseDTO fetchTrendSummary(){
        StringBuilder logMessageBuilder = new StringBuilder();

        // 1주일 전까지의 일일 리포트를 인풋으로 사용
        LocalDateTime startOfDay = LocalDateTime.now().minusWeeks(1).with(LocalTime.MIN);
        List<Summary> trendSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.DAILY), startOfDay);

        logMessageBuilder.append("### Weekly Trend Summaries ###\n\n");

        for (Summary summary : trendSummaries) {
            logMessageBuilder.append("Date: ")
                    .append(summary.getCreatedDate())
                    .append("\n")
                    .append(summary.getContent())
                    .append("\n\n");
        }

        // logMessage 문자열로 변환
        String logMessage = logMessageBuilder.toString();

        return webClient.post()
                .uri(buildURI(TREND_SUMMERY_URI))
                .bodyValue(new LogDTO.SummaryRequestDTO(logMessage))
                .retrieve()
                .bodyToMono(LogDTO.TrendSummaryResponseDTO.class)
                .block();
    }

    private LogDTO.RecommendationDTO fetchRecommendation() {
        StringBuilder logMessageBuilder = new StringBuilder();

        // 6시간 전의 요약들을 인풋으로
        LocalDateTime startOfTime = LocalDateTime.now().minusHours(6);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), startOfTime);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.GENERAL, SummaryType.ANOMALY), startOfTime);

        // 성능 요약을 로그 메시지로 변환
        logMessageBuilder.append("### Performance Summary ###\n");
        if (performanceSummaries.isEmpty()) {
            logMessageBuilder.append("- 성능 요약 내역이 없습니다.\n");
        } else {
            for (Summary summary : performanceSummaries) {
                logMessageBuilder.append("Date: ")
                        .append(summary.getCreatedDate())
                        .append("\n")
                        .append(summary.getContent())
                        .append("\n\n");
            }
        }

        // 일반 및 이상 로그 요약을 로그 메시지로 변환
        logMessageBuilder.append("\n### Application Log Summary ###\n");
        if (logSummaries.isEmpty()) {
            logMessageBuilder.append("- 로그 요약 내역이 없습니다.\n");
        } else {
            for (Summary summary : logSummaries) {
                logMessageBuilder.append("Date: ")
                        .append(summary.getCreatedDate())
                        .append("\n")
                        .append(summary.getContent())
                        .append("\n\n");
            }
        }

        // logMessage 문자열로 변환
        String logMessage = logMessageBuilder.toString();

        // LLM에 전달하기 위해 FastAPI에 요청
        return webClient.post()
                .uri(buildURI(RECOMMEND_URI))
                .bodyValue(new LogDTO.SummaryRequestDTO(logMessage))
                .retrieve()
                .bodyToMono(LogDTO.RecommendationDTO.class)
                .block();
    }

    private URI buildURI(String uri) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(uri);
        return uriBuilder.build().encode().toUri();
    }
}
