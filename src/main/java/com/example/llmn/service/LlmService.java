package com.example.llmn.service;

import com.example.llmn.controller.DTO.LogDTO;
import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.domain.*;
import com.example.llmn.repository.ProjectRepository;
import com.example.llmn.repository.SummaryRepository;
import com.example.llmn.repository.UserRepository;
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

import static com.example.llmn.core.utils.DateTimeUtils.formatLocalDateTime;
import static com.example.llmn.core.utils.UriUtils.buildURI;

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

    private static final String LOG_DATA_HEADER = "<Log Data>\n";
    private static final String PERFORMANCE_SUMMARY_HEADER = "<Performance Summary>\n";
    private static final String APPLICATION_LOG_SUMMARY_HEADER = "<Application Log Summary>\n";
    private static final String WEEKLY_TREND_HEADER = "-<Weekly Trend Summaries>\n";
    private static final String LOG_EMERGENCY_ALARM_SUFFIX = "의 로그를 점검 해보세요. 문제점이 발견되었습니다.";
    private static final String LOG_UPDATE_ALARM_SUFFIX = "의 요약이 업데이트 되었습니다.";
    private static final int METRIC_HISTORY_PREVIOUS_HOUR = 1;
    private static final String PERFORMANCE_SUMMARY_ALARM = "새로운 성능 요약이 생성 되었습니다.";
    private static final String HOURLY_SUMMARY_ALARM = "새로운 시간별 요약이 생성 되었습니다.";
    private static final String DAILY_SUMMARY_ALARM = "새로운 일일 요약이 생성 되었습니다.";
    private static final String TREND_SUMMARY_ALARM = "장기 트렌드 분석 요약이 생성 되었습니다.";
    private static final String RECOMMENDATION_ALARM = "새로운 추천 사항이 업데이트 되었습니다";
    private static final String NO_SUMMARY_DATA = "- 요약 데이터가 존재하지 않습니다.\n";

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
            LogDTO.PerformanceSummaryResponseDTO performanceSummaryDTO = fetchMetricSummary(monitoringSshInfoId);
            saveSummary(user, performanceSummaryDTO.performanceSummary(), SummaryType.PERFORMANCE);

            alarmService.generateAlarm(user.getId(), PERFORMANCE_SUMMARY_ALARM, AlarmType.UPDATE);
        }
    }

    @Transactional
    @Scheduled(cron = "0 10 * * * *")
    public void summaryHourly(){
        List<Long> userIds = userRepository.findIds();

        for(Long userId: userIds) {
            LogDTO.HourlySummaryResponseDTO hourlySummaryDTO = fetchHourlySummary(userId);

            User userRef = userRepository.getReferenceById(userId);
            saveSummary(userRef, hourlySummaryDTO.hourlySummary(), SummaryType.HOURLY);

            alarmService.generateAlarm(userId, HOURLY_SUMMARY_ALARM, AlarmType.UPDATE);
        }
    }

    @Transactional
    @Scheduled(cron = "0 55 23 * * *")
    public void summaryDaily(){
        List<Long> userIds = userRepository.findIds();

        for(Long userId: userIds) {
            LogDTO.DailySummaryResponseDTO dailySummaryDTO = fetchDailySummary(userId);

            User userRef = userRepository.getReferenceById(userId);
            saveSummary(userRef, dailySummaryDTO.dailySummary(), SummaryType.DAILY);

            alarmService.generateAlarm(userId, DAILY_SUMMARY_ALARM, AlarmType.UPDATE);
        }
    }

    @Transactional
    @Scheduled(cron = "0 45 23 * * 0")
    public void summaryTrend(){
        List<Long> userIds = userRepository.findIds();

        for(Long userId: userIds) {
            LogDTO.TrendSummaryResponseDTO trendSummaryDTO = fetchTrendSummary(userId);

            User userRef = userRepository.getReferenceById(userId);
            saveSummary(userRef, trendSummaryDTO.trendSummary(), SummaryType.TEND);

            alarmService.generateAlarm(userId, TREND_SUMMARY_ALARM, AlarmType.UPDATE);
        }
    }

    @Transactional
    @Scheduled(cron = "0 20 0,6,12,18 * * *")
    public void recommend(){
        List<Long> userIds = userRepository.findIds();

        for(Long userId: userIds) {
            fetchRecommendation(userId).ifPresent(recommendation -> {
                User userRef = userRepository.getReferenceById(userId);
                saveSummary(userRef, recommendation.recommend(), SummaryType.RECOMMENDATION);

                alarmService.generateAlarm(userId, RECOMMENDATION_ALARM, AlarmType.UPDATE);
            });
        }
    }

    private LogDTO.SummaryResponseDTO fetchLogSummary(String containerName) {
        String logContent = logService.findRecentLogs(containerName);
        String summaryRequestBody = buildSummaryRequestBody(containerName, logContent);

        return sendSummaryRequest(logSummeryUri, summaryRequestBody, LogDTO.SummaryResponseDTO.class);
    }

    private LogDTO.PerformanceSummaryResponseDTO fetchMetricSummary(Long sshInfoId){
        MetricResponse.FindMetricHistoryDTO metricHistory = metricService.findMetricHistory(METRIC_HISTORY_PREVIOUS_HOUR, sshInfoId);
        String summaryRequestBody = buildSummaryRequestBody(metricHistory);

        return sendSummaryRequest(performanceSummeryUri, summaryRequestBody, LogDTO.PerformanceSummaryResponseDTO.class);
    }

    private String buildSummaryRequestBody(MetricResponse.FindMetricHistoryDTO metricHistory) {
        StringBuilder summaryRequestBody = new StringBuilder();
        summaryRequestBody.append(PERFORMANCE_SUMMARY_HEADER);
        appendCpuMetrics(summaryRequestBody, metricHistory);
        appendMemoryMetrics(summaryRequestBody, metricHistory);
        appendNetworkInMetrics(summaryRequestBody, metricHistory);
        appendNetworkOutMetrics(summaryRequestBody, metricHistory);

        return summaryRequestBody.toString();
    }

    private LogDTO.HourlySummaryResponseDTO fetchHourlySummary(Long userId) {
        LocalDateTime startOfHour = LocalDateTime.now().withMinute(0).minusSeconds(0);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfHour);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfHour);

        StringBuilder summaryRequestBody = new StringBuilder();
        appendSummaryWithHeader(summaryRequestBody, PERFORMANCE_SUMMARY_HEADER, performanceSummaries);
        appendLogSummary(summaryRequestBody, logSummaries);

        return sendSummaryRequest(hourlySummaryUri, summaryRequestBody.toString(), LogDTO.HourlySummaryResponseDTO.class);
    }

    private LogDTO.DailySummaryResponseDTO fetchDailySummary(Long userId) {
        LocalDateTime startOfDay = LocalDateTime.now().with(LocalTime.MIN);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfDay);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfDay);

        StringBuilder summaryRequestBody = new StringBuilder();
        appendSummaryWithHeader(summaryRequestBody, PERFORMANCE_SUMMARY_HEADER, performanceSummaries);
        appendLogSummary(summaryRequestBody, logSummaries);

        return sendSummaryRequest(dailySummeryUri, summaryRequestBody.toString(), LogDTO.DailySummaryResponseDTO.class);
    }

    private LogDTO.TrendSummaryResponseDTO fetchTrendSummary(Long userId){
        LocalDateTime startOfDay = LocalDateTime.now().minusWeeks(1).with(LocalTime.MIN);
        List<Summary> dailySummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.DAILY), userId, startOfDay);

        StringBuilder summaryRequestBody = new StringBuilder();
        appendSummaryWithHeader(summaryRequestBody, WEEKLY_TREND_HEADER, dailySummaries);

        return sendSummaryRequest(trendSummeryUri, summaryRequestBody.toString(), LogDTO.TrendSummaryResponseDTO.class);
    }

    private Optional<LogDTO.RecommendationDTO> fetchRecommendation(Long userId) {
        LocalDateTime startOfTime = LocalDateTime.now().minusHours(6);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfTime);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfTime);

        String summaryRequestBody = buildRecommendRequestBody(performanceSummaries, logSummaries);
        LogDTO.RecommendationDTO response = sendSummaryRequest(recommendUri, summaryRequestBody, LogDTO.RecommendationDTO.class);

        return Optional.ofNullable(response);
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

    private void processProjectLogSummary(Project project) {
        LogDTO.SummaryResponseDTO summaryDTO = fetchLogSummary(project.getContainerName());
        if(isSummaryContentEmpty(summaryDTO)){
            return;
        }

        saveSummary(project.getUser(), project, summaryDTO.logSummary(), SummaryType.LOG);

        checkUrgency(project, summaryDTO);
        generateAlarm(project);
    }

    private boolean isSummaryContentEmpty(LogDTO.SummaryResponseDTO summaryDTO) {
        return summaryDTO.logSummary().isBlank();
    }

    private void checkUrgency(Project project, LogDTO.SummaryResponseDTO summaryDTO) {
        if(summaryDTO.isUrgent()) {
            project.updateIsUrgent(true);
            String emergencyAlarmContent = project.getProjectName() + LOG_EMERGENCY_ALARM_SUFFIX;
            alarmService.generateAlarm(project.getUser().getId(), emergencyAlarmContent, AlarmType.EMERGENCY);
        }
    }

    private void generateAlarm(Project project) {
        String updateAlarmContent = project.getProjectName() + LOG_UPDATE_ALARM_SUFFIX;
        alarmService.generateAlarm(project.getUser().getId(), updateAlarmContent, AlarmType.UPDATE);
    }

    private void appendCpuMetrics(StringBuilder builder, MetricResponse.FindMetricHistoryDTO metricHistory) {
        builder.append("1. CPU Metrics:\n");
        for (MetricResponse.CpuMetricDTO cpuMetric : metricHistory.cpuMetrics()) {
            builder.append(String.format("- Time: %s, CPU Usage: %.2f%%", cpuMetric.time(), cpuMetric.cpuUsage()))
                    .append("\n");
        }
    }

    private void appendMemoryMetrics(StringBuilder builder, MetricResponse.FindMetricHistoryDTO metricHistory) {
        builder.append("\n2. Memory Metrics:\n");
        for (MetricResponse.MemoryMetricDTO memoryMetric : metricHistory.memoryMetrics()) {
            builder.append(String.format("- Time: %s, Memory Usage: %.2f MB", memoryMetric.time(), memoryMetric.memoryUsage()))
                    .append("\n");
        }
    }

    private void appendNetworkInMetrics(StringBuilder builder, MetricResponse.FindMetricHistoryDTO metricHistory) {
        builder.append("\n3. Network In Metrics:\n");
        for (MetricResponse.NetworkInMetricDTO networkInMetric : metricHistory.networkInMetrics()) {
            builder.append(String.format("- Time: %s, Network Received: %.2f MB", networkInMetric.time(), networkInMetric.networkReceived()))
                    .append("\n");
        }
    }

    private void appendNetworkOutMetrics(StringBuilder builder, MetricResponse.FindMetricHistoryDTO metricHistory) {
        builder.append("\n4. Network Out Metrics:\n");
        for (MetricResponse.NetworkOutMetricDTO networkOutMetric : metricHistory.networkOutMetrics()) {
            builder.append(String.format("- Time: %s, Network Sent: %.2f MB", networkOutMetric.time(), networkOutMetric.networkSent()))
                    .append("\n");
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

    private String buildSummaryRequestBody(String containerName, String logMessage) {
        return new StringBuilder()
                .append(LOG_DATA_HEADER)
                .append("Application Name: ")
                .append(containerName)
                .append("\n")
                .append("Log Content: ")
                .append(logMessage)
                .append("\n")
                .toString();
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