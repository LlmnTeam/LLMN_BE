package com.example.llmn.domain.log;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.example.llmn.domain.log.model.request.SummaryReq;
import com.example.llmn.domain.log.model.response.*;
import com.example.llmn.domain.alarm.AlarmService;
import com.example.llmn.domain.alarm.AlarmType;
import com.example.llmn.domain.metric.MetricService;
import com.example.llmn.domain.metric.model.response.*;
import com.example.llmn.domain.openai.OpenAiKeyService;
import com.example.llmn.domain.project.Project;
import com.example.llmn.domain.remote.SshInfo;
import com.example.llmn.domain.remote.SshInfoRepository;
import com.example.llmn.domain.summary.Summary;
import com.example.llmn.domain.summary.SummaryType;
import com.example.llmn.domain.project.ProjectRepository;
import com.example.llmn.domain.summary.SummaryRepository;
import com.example.llmn.domain.user.User;
import com.example.llmn.domain.user.UserRepository;
import com.example.llmn.integration.elasticsearch.ElasticSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.llmn.common.utils.DateTimeUtils.formatLocalDateTime;
import static com.example.llmn.common.utils.DateTimeUtils.getTodayDateInString;
import static com.example.llmn.common.utils.UriUtils.buildURI;
import static com.example.llmn.domain.log.LogConstants.*;
import static com.example.llmn.domain.metric.MetricConstants.DEFAULT_METRIC_HISTORY_HOURS;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogScheduler {

    private final LogService logService;
    private final MetricService metricService;
    private final AlarmService alarmService;
    private final ElasticSearchService elasticSearchService;
    private final UserRepository userRepository;
    private final SummaryRepository summaryRepository;
    private final OpenAiKeyService openAiKeyService;
    private final ProjectRepository projectRepository;
    private final SshInfoRepository sshInfoRepository;
    private final WebClient webClient;

    @Value("${log.summary.uri}")
    private String logSummaryServiceUrl;

    @Value("${performance_summary.uri}")
    private String performanceSummaryServiceUrl;

    @Value("${daily_summary.uri}")
    private String dailySummaryServiceUrl;

    @Value("${hourly.summary.uri}")
    private String hourlySummaryServiceUrl;

    @Value("${trend.summary.uri}")
    private String trendSummaryServiceUrl;

    @Value("${recommend.uri}")
    private String recommendationServiceUrl;

    @Scheduled(fixedRate = 60000)
    @SuppressWarnings("rawtypes")
    public void collectAndPersistLogs() {
        List<SshInfo> sshConfigurations = sshInfoRepository.findAll();

        for (SshInfo sshConfig : sshConfigurations) {
            SearchResponse<Map> searchResponse = elasticSearchService.searchUnprocessedDocuments(
                    getCurrentLogIndexName(),
                    sshConfig.getRemoteHost(),
                    Map.class,
                    MAX_LOG_RECORDS_PER_QUERY
            );

            List<Map<String, Object>> rawLogDocuments = logService.convertElasticsearchResultToLogMap(searchResponse);
            List<Map<String, Object>> processedLogs = logService.standardizeLogFields(rawLogDocuments);

            elasticSearchService.updateDocuments(getCurrentLogIndexName(), processedLogs, sshConfig.getRemoteHost());
            logService.persistLogsToFiles(processedLogs, sshConfig.getId());
        }
    }

    @Transactional
    @Scheduled(cron = "0 0 * * * *")
    public void scheduleProjectLogSummaries() {
        List<User> users = userRepository.findAll();
        for (User user : users) {
            List<Project> activeProjects = projectRepository.findByUserId(user.getId()).stream()
                    .filter(Project::isConnected)
                    .toList();

            activeProjects.forEach(project -> generateProjectSummary(project, user.getId()));
        }
    }

    @Transactional
    @Scheduled(cron = "0 5 * * * *")
    public void schedulePerformanceSummaries() {
        List<User> users = userRepository.findAll();
        for (User user : users) {
            requestPerformanceSummary(user.getMonitoringSshId(), user.getId())
                    .ifPresent(performanceSummaryResponse -> {
                        saveSummary(user, null, performanceSummaryResponse.performanceSummary(), SummaryType.PERFORMANCE);
                        alarmService.generateAlarm(user.getId(), PERFORMANCE_SUMMARY_UPDATE_MESSAGE, AlarmType.UPDATE);
                    });
        }
    }

    @Transactional
    @Scheduled(cron = "0 10 * * * *")
    public void scheduleHourlySummaries() {
        List<Long> userIds = userRepository.findIds();
        for (Long userId : userIds) {
            requestHourlySummary(userId)
                    .ifPresent(hourlySummaryResponse -> {
                        User user = userRepository.getReferenceById(userId);
                        saveSummary(user, null, hourlySummaryResponse.hourlySummary(), SummaryType.HOURLY);
                        alarmService.generateAlarm(userId, HOURLY_SUMMARY_UPDATE_MESSAGE, AlarmType.UPDATE);
                    });
        }
    }

    @Transactional
    @Scheduled(cron = "0 55 23 * * *")
    public void scheduleDailySummaries() {
        List<Long> userIds = userRepository.findIds();
        for (Long userId : userIds) {
            requestDailySummary(userId)
                    .ifPresent(dailySummaryResponse -> {
                        User user = userRepository.getReferenceById(userId);
                        saveSummary(user, null, dailySummaryResponse.dailySummary(), SummaryType.DAILY);
                        alarmService.generateAlarm(userId, DAILY_SUMMARY_UPDATE_MESSAGE, AlarmType.UPDATE);
                    });
        }
    }

    @Transactional
    @Scheduled(cron = "0 45 23 * * 0")
    public void scheduleWeeklyTrendSummaries() {
        List<Long> userIds = userRepository.findIds();
        for (Long userId : userIds) {
            requestWeeklyTrendSummary(userId)
                    .ifPresent(trendSummaryResponse -> {
                        User user = userRepository.getReferenceById(userId);
                        saveSummary(user, null, trendSummaryResponse.trendSummary(), SummaryType.TEND);
                        alarmService.generateAlarm(userId, TREND_SUMMARY_UPDATE_MESSAGE, AlarmType.UPDATE);
                    });
        }
    }

    @Transactional
    @Scheduled(cron = "0 20 0,6,12,18 * * *")
    public void scheduleRecommendations() {
        List<Long> userIds = userRepository.findIds();
        for (Long userId : userIds) {
            requestRecommendations(userId)
                    .ifPresent(recommendationResponse -> {
                        User user = userRepository.getReferenceById(userId);
                        saveSummary(user, null, recommendationResponse.recommend(), SummaryType.RECOMMENDATION);
                        alarmService.generateAlarm(userId, RECOMMENDATION_UPDATE_MESSAGE, AlarmType.UPDATE);
                    });
        }
    }

    private void generateProjectSummary(Project project, Long userId) {
        requestLogSummaryFromService(project.getContainerName(), userId)
                .ifPresent(summaryRes -> {
                    saveSummary(project.getUser(), project, summaryRes.logSummary(), SummaryType.LOG);
                    checkForUrgentIssuesInSummary(project, summaryRes);
                    alarmService.generateAlarm(project.getUser().getId(), createLogSummaryUpdateMessage(project), AlarmType.UPDATE);
                });
    }

    private Optional<SummaryRes> requestLogSummaryFromService(String containerName, Long userId) {
        String recentLogContent = logService.findRecentLogs(containerName);

        String formattedLogData = formatLogSummaryRequestData(containerName, recentLogContent);
        SummaryRes summaryResponse = sendSummaryRequest(logSummaryServiceUrl, formattedLogData, SummaryRes.class, userId);

        return Optional.ofNullable(summaryResponse);
    }

    private String formatLogSummaryRequestData(String containerName, String logContent) {
        return LOG_DATA_SECTION_HEADER +
                "Application Name: " + containerName + "\n" +
                "Log Content: " + logContent + "\n";
    }

    private void checkForUrgentIssuesInSummary(Project project, SummaryRes summaryResponse) {
        if (summaryResponse.isUrgent()) {
            project.updateIsUrgent(true);
            String urgentAlertMessage = project.getProjectName() + LOG_EMERGENCY_ALARM_SUFFIX;
            alarmService.generateAlarm(project.getUserId(), urgentAlertMessage, AlarmType.EMERGENCY);
        }
    }

    private String createLogSummaryUpdateMessage(Project project) {
        return project.getProjectName() + LOG_UPDATE_ALARM_SUFFIX;
    }

    private Optional<PerformanceSummaryRes> requestPerformanceSummary(Long sshInfoId, Long userId) {
        FindMetricHistoryRes metricHistory = metricService.findHistoricalMetrics(DEFAULT_METRIC_HISTORY_HOURS, sshInfoId);

        String formattedMetricData = formatPerformanceMetricsData(metricHistory);
        PerformanceSummaryRes summaryResponse = sendSummaryRequest(performanceSummaryServiceUrl, formattedMetricData, PerformanceSummaryRes.class, userId);

        return Optional.ofNullable(summaryResponse);
    }

    private String formatPerformanceMetricsData(FindMetricHistoryRes metricHistory) {
        StringBuilder metricDataBuilder = new StringBuilder();
        metricDataBuilder.append(PERFORMANCE_SUMMARY_SECTION_HEADER);

        appendCpuMetricsToBuilder(metricDataBuilder, metricHistory);
        appendMemoryMetricsToBuilder(metricDataBuilder, metricHistory);
        appendNetworkInMetricsToBuilder(metricDataBuilder, metricHistory);
        appendNetworkOutMetricsToBuilder(metricDataBuilder, metricHistory);

        return metricDataBuilder.toString();
    }

    private void appendCpuMetricsToBuilder(StringBuilder builder, FindMetricHistoryRes metricHistory) {
        builder.append("1. CPU Metrics:\n");
        for (CpuMetricRes cpuMetric : metricHistory.cpuMetrics()) {
            builder.append(String.format("- Time: %s, CPU Usage: %.2f%%", cpuMetric.time(), cpuMetric.cpuUsage()))
                    .append("\n");
        }
    }

    private void appendMemoryMetricsToBuilder(StringBuilder builder, FindMetricHistoryRes metricHistory) {
        builder.append("\n2. Memory Metrics:\n");
        for (MemoryMetricRes memoryMetric : metricHistory.memoryMetrics()) {
            builder.append(String.format("- Time: %s, Memory Usage: %.2f MB", memoryMetric.time(), memoryMetric.memoryUsage()))
                    .append("\n");
        }
    }

    private void appendNetworkInMetricsToBuilder(StringBuilder builder, FindMetricHistoryRes metricHistory) {
        builder.append("\n3. Network In Metrics:\n");
        for (NetworkInMetricRes networkInMetric : metricHistory.networkInMetrics()) {
            builder.append(String.format("- Time: %s, Network Received: %.2f MB", networkInMetric.time(), networkInMetric.networkReceived()))
                    .append("\n");
        }
    }

    private void appendNetworkOutMetricsToBuilder(StringBuilder builder, FindMetricHistoryRes metricHistory) {
        builder.append("\n4. Network Out Metrics:\n");
        for (NetworkOutMetricRes networkOutMetric : metricHistory.networkOutMetrics()) {
            builder.append(String.format("- Time: %s, Network Sent: %.2f MB", networkOutMetric.time(), networkOutMetric.networkSent()))
                    .append("\n");
        }
    }

    private Optional<HourlySummaryRes> requestHourlySummary(Long userId) {
        LocalDateTime startOfHour = LocalDateTime.now().withMinute(0).minusSeconds(0);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfHour);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfHour);

        String formattedHourlySummaryData = formatCombinedSummaryData(performanceSummaries, logSummaries);
        HourlySummaryRes summaryResponse = sendSummaryRequest(hourlySummaryServiceUrl, formattedHourlySummaryData, HourlySummaryRes.class, userId);

        return Optional.ofNullable(summaryResponse);
    }

    private Optional<DailySummaryRes> requestDailySummary(Long userId) {
        LocalDateTime startOfDay = LocalDateTime.now().with(LocalTime.MIN);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfDay);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfDay);

        String formattedDailySummaryData = formatCombinedSummaryData(performanceSummaries, logSummaries);
        DailySummaryRes summaryResponse = sendSummaryRequest(dailySummaryServiceUrl, formattedDailySummaryData, DailySummaryRes.class, userId);

        return Optional.ofNullable(summaryResponse);
    }

    private Optional<TrendSummaryRes> requestWeeklyTrendSummary(Long userId) {
        LocalDateTime startOfDay = LocalDateTime.now().minusWeeks(1).with(LocalTime.MIN);
        List<Summary> dailySummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.DAILY), userId, startOfDay);

        String formattedTrendSummaryData = formatWeeklyTrendData(dailySummaries);
        TrendSummaryRes summaryResponse = sendSummaryRequest(trendSummaryServiceUrl, formattedTrendSummaryData, TrendSummaryRes.class, userId);

        return Optional.ofNullable(summaryResponse);
    }

    private String formatWeeklyTrendData(List<Summary> dailySummaries) {
        StringBuilder summaryDataBuilder = new StringBuilder();
        appendFormattedSummaryWithHeader(summaryDataBuilder, WEEKLY_TREND_SECTION_HEADER, dailySummaries);

        return summaryDataBuilder.toString();
    }

    private Optional<RecommendationRes> requestRecommendations(Long userId) {
        LocalDateTime startOfTime = LocalDateTime.now().minusHours(6);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfTime);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfTime);

        String formattedRecommendationData = formatCombinedSummaryData(performanceSummaries, logSummaries);
        RecommendationRes recommendationResponse = sendSummaryRequest(recommendationServiceUrl, formattedRecommendationData, RecommendationRes.class, userId);

        return Optional.ofNullable(recommendationResponse);
    }

    private void appendFormattedSummaryWithHeader(StringBuilder summaryDataBuilder, String header, List<Summary> summaries) {
        summaryDataBuilder.append(header);

        if (summaries.isEmpty()) {
            summaryDataBuilder.append(EMPTY_SUMMARY_DATA_MESSAGE);
        } else {
            for (Summary summary : summaries) {
                summaryDataBuilder.append("Summary Date: ")
                        .append(formatLocalDateTime(summary.getCreatedDate())) // 날짜 형식 변환
                        .append("\n")
                        .append("Summary Content: ")
                        .append(summary.getContent()) // 요약 내용 추가
                        .append("\n");
            }
        }
    }

    private void appendFormattedLogSummary(StringBuilder summaryDataBuilder, List<Summary> summaries) {
        summaryDataBuilder.append(APPLICATION_LOG_SUMMARY_SECTION_HEADER);

        if (summaries.isEmpty()) {
            summaryDataBuilder.append(EMPTY_SUMMARY_DATA_MESSAGE);
        } else {
            for (Summary summary : summaries) {
                summaryDataBuilder.append("Application Name: ")
                        .append(summary.getProject().getContainerName())
                        .append("\n")
                        .append("Summary Date: ")
                        .append(formatLocalDateTime(summary.getCreatedDate())) // 날짜 형식 변환
                        .append("\n")
                        .append("Summary Content: ")
                        .append(summary.getContent()) // 요약 내용 추가
                        .append("\n");
            }
        }
    }

    private String formatCombinedSummaryData(List<Summary> performanceSummaries, List<Summary> logSummaries) {
        StringBuilder summaryDataBuilder = new StringBuilder();
        appendFormattedSummaryWithHeader(summaryDataBuilder, PERFORMANCE_SUMMARY_SECTION_HEADER, performanceSummaries);
        appendFormattedLogSummary(summaryDataBuilder, logSummaries);

        return summaryDataBuilder.toString();
    }

    private <T> T sendSummaryRequest(String serviceUrl, String requestContent, Class<T> responseType, Long userId) {
        try {
            String apiKey = openAiKeyService.getOpenAiKey(userId);
            return webClient.post()
                    .uri(buildURI(serviceUrl))
                    .bodyValue(new SummaryReq(requestContent, apiKey))
                    .retrieve()
                    .bodyToMono(responseType)
                    .block();
        } catch (Exception e) {
            return null;
        }
    }

    private void saveSummary(User user, Project project, String content, SummaryType summaryType) {
        Summary summary = Summary.builder()
                .user(user)
                .project(project)
                .content(content)
                .summaryType(summaryType)
                .build();
        summaryRepository.save(summary);
    }

    private String getCurrentLogIndexName() {
        return ELASTICSEARCH_LOG_INDEX_PREFIX + getTodayDateInString(); // 오늘 날짜를 기반으로 인덱스 이름 생성
    }
}