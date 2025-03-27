package com.example.llmn.domain.log;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.log.model.request.SummaryReq;
import com.example.llmn.domain.log.model.response.*;
import com.example.llmn.domain.alarm.AlarmService;
import com.example.llmn.domain.alarm.AlarmType;
import com.example.llmn.domain.metric.MetricService;
import com.example.llmn.domain.metric.model.response.*;
import com.example.llmn.domain.other.OpenAiKey;
import com.example.llmn.domain.other.OpenAiKeyRepository;
import com.example.llmn.domain.project.Project;
import com.example.llmn.domain.summary.Summary;
import com.example.llmn.domain.summary.SummaryType;
import com.example.llmn.domain.project.ProjectRepository;
import com.example.llmn.domain.summary.SummaryRepository;
import com.example.llmn.domain.user.User;
import com.example.llmn.domain.user.UserRepository;
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
import java.util.Optional;

import static com.example.llmn.common.utils.DateTimeUtils.formatLocalDateTime;
import static com.example.llmn.common.utils.UriUtils.buildURI;
import static com.example.llmn.domain.log.LogConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogScheduler {

    private final LogService logService;
    private final MetricService metricService;
    private final AlarmService alarmService;
    private final UserRepository userRepository;
    private final SummaryRepository summaryRepository;
    private final OpenAiKeyRepository openAiKeyRepository;
    private final ProjectRepository projectRepository;
    private final WebClient webClient;

    @Value("${log.summary.uri}")
    private String logSummeryUri;

    @Value("${performance_summary.uri}")
    private String performanceSummeryUri;

    @Value("${daily_summary.uri}")
    private String dailySummeryUri;

    @Value("${hourly.summary.uri}")
    private String hourlySummaryUri;

    @Value("${trend.summary.uri}")
    private String trendSummeryUri;

    @Value("${recommend.uri}")
    private String recommendUri;

    private static final int METRIC_HISTORY_PREVIOUS_HOUR = 1;

    @Transactional
    @Scheduled(cron = "0 0 * * * *")
    public void summaryProjectLog() {
        List<User> users = userRepository.findAll();
        for(User user : users){
            List<Project> projects = projectRepository.findByUserId(user.getId()).stream()
                    .filter(Project::isConnected)
                    .toList();

            projects.forEach(project -> processProjectLogSummary(project, user.getId()));
        }
    }

    @Transactional
    @Scheduled(cron = "0 5 * * * *")
    public void summaryPerformance() {
        List<User> users = userRepository.findAll();
        for (User user : users) {
            Long monitoringSshInfoId = user.getMonitoringSshId();

            fetchMetricSummary(monitoringSshInfoId, user.getId())
                    .ifPresent(performanceSummaryRes -> {
                        saveSummary(user, performanceSummaryRes.performanceSummary(), SummaryType.PERFORMANCE);
                        alarmService.generateAlarm(user.getId(), PERFORMANCE_SUMMARY_ALARM, AlarmType.UPDATE);
                    });
        }
    }

    @Transactional
    @Scheduled(cron = "0 10 * * * *")
    public void summaryHourly() {
        List<Long> userIds = userRepository.findIds();
        for (Long userId : userIds) {
            fetchHourlySummary(userId)
                    .ifPresent(hourlySummaryRes -> {
                        User user = userRepository.getReferenceById(userId);
                        saveSummary(user, hourlySummaryRes.hourlySummary(), SummaryType.HOURLY);
                        alarmService.generateAlarm(userId, HOURLY_SUMMARY_ALARM, AlarmType.UPDATE);
                    });
        }
    }

    @Transactional
    @Scheduled(cron = "0 55 23 * * *")
    public void summaryDaily() {
        List<Long> userIds = userRepository.findIds();
        for (Long userId : userIds) {
            fetchDailySummary(userId)
                    .ifPresent(dailySummaryRes -> {
                        User user = userRepository.getReferenceById(userId);
                        saveSummary(user, dailySummaryRes.dailySummary(), SummaryType.DAILY);
                        alarmService.generateAlarm(userId, DAILY_SUMMARY_ALARM, AlarmType.UPDATE);
                    });
        }
    }

    @Transactional
    @Scheduled(cron = "0 45 23 * * 0")
    public void summaryTrend() {
        List<Long> userIds = userRepository.findIds();
        for (Long userId : userIds) {
            fetchTrendSummary(userId)
                    .ifPresent(trendSummaryRes -> {
                        User user = userRepository.getReferenceById(userId);
                        saveSummary(user, trendSummaryRes.trendSummary(), SummaryType.TEND);
                        alarmService.generateAlarm(userId, TREND_SUMMARY_ALARM, AlarmType.UPDATE);
                    });
        }
    }

    @Transactional
    @Scheduled(cron = "0 20 0,6,12,18 * * *")
    public void recommend() {
        List<Long> userIds = userRepository.findIds();
        for (Long userId : userIds) {
            fetchRecommendation(userId)
                    .ifPresent(recommendationDTO -> {
                        User user = userRepository.getReferenceById(userId);
                        saveSummary(user, recommendationDTO.recommend(), SummaryType.RECOMMENDATION);
                        alarmService.generateAlarm(userId, RECOMMENDATION_ALARM, AlarmType.UPDATE);
                    });
        }
    }

    private void processProjectLogSummary(Project project, Long userId) {
        String containerName = project.getContainerName();
        fetchLogSummary(containerName, userId)
                .ifPresent(summaryRes -> {
                    saveSummary(project.getUser(), project, summaryRes.logSummary(), SummaryType.LOG);
                    checkUrgency(project, summaryRes);
                    alarmService.generateAlarm(project.getUser().getId(), createAlarmContent(project), AlarmType.UPDATE);
                });
    }

    private Optional<SummaryRes> fetchLogSummary(String containerName, Long userId) {
        String logContent = logService.findRecentLogs(containerName);

        String summaryRequestBody = buildSummaryRequestBody(containerName, logContent);
        SummaryRes responseDTO = sendSummaryRequest(logSummeryUri, summaryRequestBody, SummaryRes.class, userId);

        return Optional.ofNullable(responseDTO);
    }

    private String buildSummaryRequestBody(String containerName, String logMessage) {
        return LOG_DATA_HEADER +
                "Application Name: " + containerName + "\n" +
                "Log Content: " + logMessage + "\n";
    }

    private void checkUrgency(Project project, SummaryRes summaryRes) {
        if (summaryRes.isUrgent()) {
            project.updateIsUrgent(true);
            String emergencyAlarmContent = project.getProjectName() + LOG_EMERGENCY_ALARM_SUFFIX;
            alarmService.generateAlarm(project.getUser().getId(), emergencyAlarmContent, AlarmType.EMERGENCY);
        }
    }

    private String createAlarmContent(Project project) {
        return project.getProjectName() + LOG_UPDATE_ALARM_SUFFIX;
    }

    private Optional<PerformanceSummaryRes> fetchMetricSummary(Long sshInfoId, Long userId) {
        FindMetricHistoryRes metricHistory = metricService.findMetricHistory(METRIC_HISTORY_PREVIOUS_HOUR, sshInfoId);

        String summaryRequestBody = buildSummaryRequestBody(metricHistory);
        PerformanceSummaryRes responseDTO = sendSummaryRequest(performanceSummeryUri, summaryRequestBody, PerformanceSummaryRes.class, userId);

        return Optional.ofNullable(responseDTO);
    }

    private String buildSummaryRequestBody(FindMetricHistoryRes metricHistory) {
        StringBuilder summaryRequestBody = new StringBuilder();
        summaryRequestBody.append(PERFORMANCE_SUMMARY_HEADER);
        appendCpuMetrics(summaryRequestBody, metricHistory);
        appendMemoryMetrics(summaryRequestBody, metricHistory);
        appendNetworkInMetrics(summaryRequestBody, metricHistory);
        appendNetworkOutMetrics(summaryRequestBody, metricHistory);

        return summaryRequestBody.toString();
    }

    private void appendCpuMetrics(StringBuilder builder, FindMetricHistoryRes metricHistory) {
        builder.append("1. CPU Metrics:\n");
        for (CpuMetricRes cpuMetric : metricHistory.cpuMetrics()) {
            builder.append(String.format("- Time: %s, CPU Usage: %.2f%%", cpuMetric.time(), cpuMetric.cpuUsage()))
                    .append("\n");
        }
    }

    private void appendMemoryMetrics(StringBuilder builder, FindMetricHistoryRes metricHistory) {
        builder.append("\n2. Memory Metrics:\n");
        for (MemoryMetricRes memoryMetric : metricHistory.memoryMetrics()) {
            builder.append(String.format("- Time: %s, Memory Usage: %.2f MB", memoryMetric.time(), memoryMetric.memoryUsage()))
                    .append("\n");
        }
    }

    private void appendNetworkInMetrics(StringBuilder builder, FindMetricHistoryRes metricHistory) {
        builder.append("\n3. Network In Metrics:\n");
        for (NetworkInMetricRes networkInMetric : metricHistory.networkInMetrics()) {
            builder.append(String.format("- Time: %s, Network Received: %.2f MB", networkInMetric.time(), networkInMetric.networkReceived()))
                    .append("\n");
        }
    }

    private void appendNetworkOutMetrics(StringBuilder builder, FindMetricHistoryRes metricHistory) {
        builder.append("\n4. Network Out Metrics:\n");
        for (NetworkOutMetricRes networkOutMetric : metricHistory.networkOutMetrics()) {
            builder.append(String.format("- Time: %s, Network Sent: %.2f MB", networkOutMetric.time(), networkOutMetric.networkSent()))
                    .append("\n");
        }
    }

    private Optional<HourlySummaryRes> fetchHourlySummary(Long userId) {
        LocalDateTime startOfHour = LocalDateTime.now().withMinute(0).minusSeconds(0);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfHour);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfHour);

        String summaryRequestBody = buildHourlySummaryRequestBody(performanceSummaries, logSummaries);
        HourlySummaryRes responseDTO = sendSummaryRequest(hourlySummaryUri, summaryRequestBody, HourlySummaryRes.class, userId);

        return Optional.ofNullable(responseDTO);
    }

    private String buildHourlySummaryRequestBody(List<Summary> performanceSummaries, List<Summary> logSummaries) {
        StringBuilder summaryRequestBody = new StringBuilder();
        appendSummaryWithHeader(summaryRequestBody, PERFORMANCE_SUMMARY_HEADER, performanceSummaries);
        appendLogSummary(summaryRequestBody, logSummaries);

        return summaryRequestBody.toString();
    }

    private Optional<DailySummaryRes> fetchDailySummary(Long userId) {
        LocalDateTime startOfDay = LocalDateTime.now().with(LocalTime.MIN);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfDay);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfDay);

        String summaryRequestBody = buildDailySummaryRequestBody(performanceSummaries, logSummaries);
        DailySummaryRes responseDTO = sendSummaryRequest(dailySummeryUri, summaryRequestBody, DailySummaryRes.class, userId);

        return Optional.ofNullable(responseDTO);
    }

    private String buildDailySummaryRequestBody(List<Summary> performanceSummaries, List<Summary> logSummaries) {
        StringBuilder summaryRequestBody = new StringBuilder();
        appendSummaryWithHeader(summaryRequestBody, PERFORMANCE_SUMMARY_HEADER, performanceSummaries);
        appendLogSummary(summaryRequestBody, logSummaries);

        return summaryRequestBody.toString();
    }

    private Optional<TrendSummaryRes> fetchTrendSummary(Long userId) {
        LocalDateTime startOfDay = LocalDateTime.now().minusWeeks(1).with(LocalTime.MIN);
        List<Summary> dailySummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.DAILY), userId, startOfDay);

        String summaryRequestBody = buildTendSummaryRequestBody(dailySummaries);
        TrendSummaryRes responseDTO = sendSummaryRequest(trendSummeryUri, summaryRequestBody, TrendSummaryRes.class, userId);

        return Optional.ofNullable(responseDTO);
    }

    private String buildTendSummaryRequestBody(List<Summary> dailySummaries) {
        StringBuilder summaryRequestBody = new StringBuilder();
        appendSummaryWithHeader(summaryRequestBody, WEEKLY_TREND_HEADER, dailySummaries);

        return summaryRequestBody.toString();
    }

    private Optional<RecommendationRes> fetchRecommendation(Long userId) {
        LocalDateTime startOfTime = LocalDateTime.now().minusHours(6);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfTime);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfTime);

        String summaryRequestBody = buildRecommendRequestBody(performanceSummaries, logSummaries);
        RecommendationRes responseDTO = sendSummaryRequest(recommendUri, summaryRequestBody, RecommendationRes.class, userId);

        return Optional.ofNullable(responseDTO);
    }

    private String buildRecommendRequestBody(List<Summary> performanceSummaries, List<Summary> logSummaries) {
        StringBuilder summaryRequestBody = new StringBuilder();
        appendSummaryWithHeader(summaryRequestBody, PERFORMANCE_SUMMARY_HEADER, performanceSummaries);
        appendLogSummary(summaryRequestBody, logSummaries);

        return summaryRequestBody.toString();
    }

    private void appendSummaryWithHeader(StringBuilder summaryRequestBody, String header, List<Summary> summaries) {
        summaryRequestBody.append(header);

        if (summaries.isEmpty()) {
            summaryRequestBody.append(NO_SUMMARY_DATA);
        } else {
            for (Summary summary : summaries) {
                summaryRequestBody.append("Summary Date: ")
                        .append(formatLocalDateTime(summary.getCreatedDate())) // 날짜 형식 변환
                        .append("\n")
                        .append("Summary Content: ")
                        .append(summary.getContent()) // 요약 내용 추가
                        .append("\n");
            }
        }
    }

    private void appendLogSummary(StringBuilder summaryRequestBody, List<Summary> summaries) {
        summaryRequestBody.append(APPLICATION_LOG_SUMMARY_HEADER);

        if (summaries.isEmpty()) {
            summaryRequestBody.append(NO_SUMMARY_DATA);
        } else {
            for (Summary summary : summaries) {
                summaryRequestBody.append("Application Name: ")
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

    private <T> T sendSummaryRequest(String uri, String requestContent, Class<T> responseType, Long userId) {
        try {
            String apiKey = getOpenAiKey(userId);
            return webClient.post()
                    .uri(buildURI(uri))
                    .bodyValue(new SummaryReq(requestContent, apiKey))
                    .retrieve()
                    .bodyToMono(responseType)
                    .block();
        } catch (Exception e) {
            return null;
        }
    }

    private String getOpenAiKey(Long userId) {
        return openAiKeyRepository.findByUser_Id(userId)
                .map(OpenAiKey::getKeyValue)
                .orElseThrow(() -> new CustomException(ExceptionCode.API_KEY_NOT_FOUND));
    }

    private void saveSummary(User user, String content, SummaryType summaryType) {
        Summary summary = Summary.builder()
                .user(user)
                .content(content)
                .summaryType(summaryType)
                .build();
        summaryRepository.save(summary);
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
}