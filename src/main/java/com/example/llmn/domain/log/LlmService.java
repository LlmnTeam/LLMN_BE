package com.example.llmn.domain.log;

import com.example.llmn.domain.metric.MetricResponse;
import com.example.llmn.domain.alarm.AlarmService;
import com.example.llmn.domain.alarm.AlarmType;
import com.example.llmn.domain.metric.MetricService;
import com.example.llmn.domain.metric.model.response.*;
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
public class LlmService {

    private final LogService logService;
    private final MetricService metricService;
    private final AlarmService alarmService;
    private final UserRepository userRepository;
    private final SummaryRepository summaryRepository;
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
    public void summaryProjectLog(){
        List<Project> projects = projectRepository.findAllWithUser().stream()
                .filter(Project::isConnected)
                .toList();

        projects.forEach(this::processProjectLogSummary);
    }

    @Transactional
    @Scheduled(cron = "0 5 * * * *")
    public void summaryPerformance(){
        List<User> users = userRepository.findAll();

        for(User user : users) {
            Long monitoringSshInfoId = user.getMonitoringSshId();

            fetchMetricSummary(monitoringSshInfoId).ifPresent(performanceSummaryDTO -> {
                saveSummary(user, performanceSummaryDTO.performanceSummary(), SummaryType.PERFORMANCE);
                alarmService.generateAlarm(user.getId(), PERFORMANCE_SUMMARY_ALARM, AlarmType.UPDATE);
            });
        }
    }

    @Transactional
    @Scheduled(cron = "0 10 * * * *")
    public void summaryHourly(){
        List<Long> userIds = userRepository.findIds();

        for(Long userId: userIds) {
            fetchHourlySummary(userId).ifPresent(hourlySummaryDTO -> {
                User userRef = userRepository.getReferenceById(userId);
                saveSummary(userRef, hourlySummaryDTO.hourlySummary(), SummaryType.HOURLY);
                alarmService.generateAlarm(userId, HOURLY_SUMMARY_ALARM, AlarmType.UPDATE);
            });
        }
    }

    @Transactional
    @Scheduled(cron = "0 55 23 * * *")
    public void summaryDaily(){
        List<Long> userIds = userRepository.findIds();

        for(Long userId: userIds) {
            fetchDailySummary(userId).ifPresent(dailySummaryDTO -> {
                User userRef = userRepository.getReferenceById(userId);
                saveSummary(userRef, dailySummaryDTO.dailySummary(), SummaryType.DAILY);
                alarmService.generateAlarm(userId, DAILY_SUMMARY_ALARM, AlarmType.UPDATE);
            });
        }
    }

    @Transactional
    @Scheduled(cron = "0 45 23 * * 0")
    public void summaryTrend(){
        List<Long> userIds = userRepository.findIds();

        for(Long userId: userIds) {
            fetchTrendSummary(userId).ifPresent(trendSummaryDTO -> {
                User userRef = userRepository.getReferenceById(userId);
                saveSummary(userRef, trendSummaryDTO.trendSummary(), SummaryType.TEND);

                alarmService.generateAlarm(userId, TREND_SUMMARY_ALARM, AlarmType.UPDATE);
            });
        }
    }

    @Transactional
    @Scheduled(cron = "0 20 0,6,12,18 * * *")
    public void recommend(){
        List<Long> userIds = userRepository.findIds();

        for(Long userId: userIds) {
            fetchRecommendation(userId).ifPresent(recommendationDTO -> {
                User userRef = userRepository.getReferenceById(userId);
                saveSummary(userRef, recommendationDTO.recommend(), SummaryType.RECOMMENDATION);

                alarmService.generateAlarm(userId, RECOMMENDATION_ALARM, AlarmType.UPDATE);
            });
        }
    }

    private void processProjectLogSummary(Project project) {
        String containerName = project.getContainerName();

        fetchLogSummary(containerName).ifPresent(logSummaryDTO -> {
            saveSummary(project.getUser(), project, logSummaryDTO.logSummary(), SummaryType.LOG);

            checkUrgency(project, logSummaryDTO);
            alarmService.generateAlarm(project.getUser().getId(), createAlarmContent(project), AlarmType.UPDATE);
        });
    }

    private Optional<LogDTO.SummaryResponseDTO> fetchLogSummary(String containerName) {
        String logContent = logService.findRecentLogs(containerName);

        String summaryRequestBody = buildSummaryRequestBody(containerName, logContent);
        LogDTO.SummaryResponseDTO responseDTO = sendSummaryRequest(logSummeryUri, summaryRequestBody, LogDTO.SummaryResponseDTO.class);

        return Optional.ofNullable(responseDTO);
    }

    private String buildSummaryRequestBody(String containerName, String logMessage) {
        return LOG_DATA_HEADER +
                "Application Name: " + containerName + "\n" +
                "Log Content: " + logMessage + "\n";
    }

    private void checkUrgency(Project project, LogDTO.SummaryResponseDTO summaryDTO) {
        if(summaryDTO.isUrgent()) {
            project.updateIsUrgent(true);
            String emergencyAlarmContent = project.getProjectName() + LOG_EMERGENCY_ALARM_SUFFIX;
            alarmService.generateAlarm(project.getUser().getId(), emergencyAlarmContent, AlarmType.EMERGENCY);
        }
    }

    private String createAlarmContent(Project project) {
        return project.getProjectName() + LOG_UPDATE_ALARM_SUFFIX;
    }

    private Optional<LogDTO.PerformanceSummaryResponseDTO> fetchMetricSummary(Long sshInfoId){
        FindMetricHistoryRes metricHistory = metricService.findMetricHistory(METRIC_HISTORY_PREVIOUS_HOUR, sshInfoId);

        String summaryRequestBody = buildSummaryRequestBody(metricHistory);
        LogDTO.PerformanceSummaryResponseDTO responseDTO = sendSummaryRequest(performanceSummeryUri, summaryRequestBody, LogDTO.PerformanceSummaryResponseDTO.class);

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

    private Optional<LogDTO.HourlySummaryResponseDTO> fetchHourlySummary(Long userId) {
        LocalDateTime startOfHour = LocalDateTime.now().withMinute(0).minusSeconds(0);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfHour);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfHour);

        String summaryRequestBody = buildHourlySummaryRequestBody(performanceSummaries, logSummaries);
        LogDTO.HourlySummaryResponseDTO responseDTO = sendSummaryRequest(hourlySummaryUri, summaryRequestBody, LogDTO.HourlySummaryResponseDTO.class);

        return Optional.ofNullable(responseDTO);
    }

    private String buildHourlySummaryRequestBody(List<Summary> performanceSummaries, List<Summary> logSummaries) {
        StringBuilder summaryRequestBody = new StringBuilder();
        appendSummaryWithHeader(summaryRequestBody, PERFORMANCE_SUMMARY_HEADER, performanceSummaries);
        appendLogSummary(summaryRequestBody, logSummaries);

        return summaryRequestBody.toString();
    }

    private Optional<LogDTO.DailySummaryResponseDTO> fetchDailySummary(Long userId) {
        LocalDateTime startOfDay = LocalDateTime.now().with(LocalTime.MIN);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfDay);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfDay);

        String summaryRequestBody = buildDailySummaryRequestBody(performanceSummaries, logSummaries);
        LogDTO.DailySummaryResponseDTO responseDTO = sendSummaryRequest(dailySummeryUri, summaryRequestBody, LogDTO.DailySummaryResponseDTO.class);

        return Optional.ofNullable(responseDTO);
    }

    private String buildDailySummaryRequestBody(List<Summary> performanceSummaries, List<Summary> logSummaries) {
        StringBuilder summaryRequestBody = new StringBuilder();
        appendSummaryWithHeader(summaryRequestBody, PERFORMANCE_SUMMARY_HEADER, performanceSummaries);
        appendLogSummary(summaryRequestBody, logSummaries);

        return summaryRequestBody.toString();
    }

    private Optional<LogDTO.TrendSummaryResponseDTO> fetchTrendSummary(Long userId){
        LocalDateTime startOfDay = LocalDateTime.now().minusWeeks(1).with(LocalTime.MIN);
        List<Summary> dailySummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.DAILY), userId, startOfDay);

        String summaryRequestBody = buildTendSummaryRequestBody(dailySummaries);
        LogDTO.TrendSummaryResponseDTO responseDTO = sendSummaryRequest(trendSummeryUri, summaryRequestBody, LogDTO.TrendSummaryResponseDTO.class);

        return Optional.ofNullable(responseDTO);
    }

    private String buildTendSummaryRequestBody(List<Summary> dailySummaries) {
        StringBuilder summaryRequestBody = new StringBuilder();
        appendSummaryWithHeader(summaryRequestBody, WEEKLY_TREND_HEADER, dailySummaries);

        return summaryRequestBody.toString();
    }

    private Optional<LogDTO.RecommendationDTO> fetchRecommendation(Long userId) {
        LocalDateTime startOfTime = LocalDateTime.now().minusHours(6);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfTime);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfTime);

        String summaryRequestBody = buildRecommendRequestBody(performanceSummaries, logSummaries);
        LogDTO.RecommendationDTO responseDTO = sendSummaryRequest(recommendUri, summaryRequestBody, LogDTO.RecommendationDTO.class);

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

    private <T> T sendSummaryRequest(String uri, String requestContent, Class<T> responseType) {
        try {
            return webClient.post()
                    .uri(buildURI(uri))
                    .bodyValue(new LogDTO.SummaryRequestDTO(requestContent))
                    .retrieve()
                    .bodyToMono(responseType)
                    .block();
        } catch (Exception e) {
            return null;
        }
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