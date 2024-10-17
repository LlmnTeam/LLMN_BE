package com.example.llmn.service;

import com.example.llmn.controller.DTO.LogDTO;
import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.domain.*;
import com.example.llmn.repository.ProjectRepository;
import com.example.llmn.repository.SummaryRepository;
import com.example.llmn.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LlmService {

    private final LogService logService;
    private final MetricService metricService;
    private final AlarmService alarmService;
    private final UserRepository userRepository;
    private final SummaryRepository summaryRepository;
    private final ProjectRepository projectRepository;
    private final WebClient webClient;

    @Value("${log.summary.uri}")
    private String LOG_SUMMERY_URI;

    @Value("${performance_summary.uri}")
    private String PERFORMANCE_SUMMERY_URI;

    @Value("${daily_summary.uri}")
    private String DAILY_SUMMERY_URI;

    @Value("${hourly.summary.uri}")
    private String HOURLY_SUMMARY_URI;

    @Value("${trend.summary.uri}")
    private String TREND_SUMMERY_URI;

    @Value("${recommend.uri}")
    private String RECOMMEND_URI;

    private static final String LOG_DATA_HEADER = "<Log Data>\n";
    private static final String PERFORMANCE_SUMMARY_HEADER = "<Performance Summary>\n";
    private static final String APPLICATION_LOG_SUMMARY_HEADER = "<Application Log Summary>\n";
    private static final String WEEKLY_TREND_HEADER = "-<Weekly Trend Summaries>\n";
    private static final String LOG_EMERGENCY_ALARM_SUFFIX = "의 로그를 점검 해보세요. 문제점이 발견되었습니다.";
    private static final String LOG_UPDATE_ALARM_SUFFIX = "의 요약이 업데이트 되었습니다.";
    private static final int METRIC_HISTORY_PREVIOUS_HOUR = 1;
    private static final String PERFORMANCE_SUMMARY_ALARM = "새로운 성능 요약이 생성 되었습니다.";
    private static final String DAILY_SUMMARY_ALARM = "새로운 일일 요약이 생성 되었습니다.";
    private static final String TREND_SUMMARY_ALARM = "장기 트렌드 분석 요약이 생성 되었습니다.";
    private static final String RECOMMENDATION_ALARM = "새로운 추천 사항이 업데이트 되었습니다";
    private static final String NO_SUMMARY_DATA = "- 요약 데이터가 존재하지 않습니다.\n";

    @Transactional
    @Scheduled(cron = "0 0 * * * *") // 매시 0분
    public void summaryProjectLog(){
        // user랑 패치 조인해서 조회
        List<Project> projects = projectRepository.findAllWithUser().stream()
                .filter(project -> !project.getContainerStatus().equals(ContainerStatus.NOT_CONNECTED))
                .toList();

        projects.stream()
                .filter(project -> !project.getContainerStatus().equals(ContainerStatus.NOT_CONNECTED))
                .forEach(project -> {
                    LogDTO.SummaryResponseDTO summaryDTO = fetchLogSummary(project.getContainerName());

                    if(summaryDTO == null){
                        return;
                    }

                    // 긴급 알람 업데이트
                    if(summaryDTO.isUrgent()) {
                        project.updateIsUrgent(true);
                        String emergencyAlarmContent = project.getProjectName() + LOG_EMERGENCY_ALARM_SUFFIX;
                        alarmService.generateAlarm(project.getUser().getId(), emergencyAlarmContent, AlarmType.EMERGENCY);
                    }

                    // 로그 요약 저장
                    Summary logSummary = Summary.builder()
                            .user(project.getUser())
                            .project(project)
                            .content(summaryDTO.logSummary())
                            .summaryType(SummaryType.LOG)
                            .build();

                    summaryRepository.save(logSummary);

                    // 업데이트 알람 생성
                    String updateAlarmContent = project.getProjectName() + LOG_UPDATE_ALARM_SUFFIX;
                    alarmService.generateAlarm(project.getUser().getId(), updateAlarmContent, AlarmType.UPDATE);
                });
    }

    @Transactional
    @Scheduled(cron = "0 5 * * * *") // 매시 5분에
    public void summaryPerformance(){
        List<User> users = userRepository.findAll();

        for(User user : users) {
            Long monitoringSshInfoId = user.getMonitoringSshId();
            LogDTO.PerformanceSummaryResponseDTO performanceSummaryDTO = fetchMetricSummary(monitoringSshInfoId);

            Summary performanceSummary = Summary.builder()
                    .user(user)
                    .content(performanceSummaryDTO.performanceSummary())
                    .summaryType(SummaryType.PERFORMANCE)
                    .build();

            summaryRepository.save(performanceSummary);

            // 업데이트 알람 생성
            alarmService.generateAlarm(user.getId(), PERFORMANCE_SUMMARY_ALARM, AlarmType.UPDATE);

            System.out.println(performanceSummaryDTO.performanceSummary());
        }
    }

    @Transactional
    @Scheduled(cron = "0 10 * * * *") // 매시 10분에
    public void summaryHourly(){
        List<Long> userIds = userRepository.findIds();

        for(Long userId: userIds) {
            LogDTO.HourlySummaryResponseDTO hourlySummaryDTO = fetchHourlySummary(userId);

            User userRef = userRepository.getReferenceById(userId);
            Summary hourlySummary = Summary.builder()
                    .user(userRef)
                    .content(hourlySummaryDTO.hourlySummary())
                    .summaryType(SummaryType.HOURLY)
                    .build();

            summaryRepository.save(hourlySummary);

            System.out.println(hourlySummaryDTO.hourlySummary());
        }
    }

    @Transactional
    @Scheduled(cron = "0 55 23 * * *") // 매일 11시 55분
    public void summaryDaily(){
        List<Long> userIds = userRepository.findIds();

        for(Long userId: userIds) {
            LogDTO.DailySummaryResponseDTO dailySummaryDTO = fetchDailySummary(userId);

            User userRef = userRepository.getReferenceById(userId);
            Summary dailySummary = Summary.builder()
                    .user(userRef)
                    .content(dailySummaryDTO.dailySummary())
                    .summaryType(SummaryType.DAILY)
                    .build();

            summaryRepository.save(dailySummary);

            // 업데이트 알람 생성
            alarmService.generateAlarm(userId, DAILY_SUMMARY_ALARM, AlarmType.UPDATE);

            System.out.println(dailySummaryDTO.dailySummary());
        }
    }

    @Transactional
    @Scheduled(cron = "0 45 23 * * 0") // 매주 일요일 11시 45분
    public void summaryTrend(){
        List<Long> userIds = userRepository.findIds();

        for(Long userId: userIds) {
            LogDTO.TrendSummaryResponseDTO trendSummaryDTO = fetchTrendSummary(userId);

            User userRef = userRepository.getReferenceById(userId);
            Summary trendSummary = Summary.builder()
                    .user(userRef)
                    .content(trendSummaryDTO.trendSummary())
                    .summaryType(SummaryType.TEND)
                    .build();

            summaryRepository.save(trendSummary);

            // 업데이트 알람 생성
            alarmService.generateAlarm(userId, TREND_SUMMARY_ALARM, AlarmType.UPDATE);

            System.out.println(trendSummaryDTO.trendSummary());
        }
    }

    @Transactional
    @Scheduled(cron = "0 20 0,6,12,18 * * *") // 6시간 간격으로 20분에
    public void recommend(){
        List<Long> userIds = userRepository.findIds();

        for(Long userId: userIds) {
            LogDTO.RecommendationDTO recommendationDTO = fetchRecommendation(userId);

            User userRef = userRepository.getReferenceById(userId);
            Summary recommend = Summary.builder()
                    .user(userRef)
                    .content(recommendationDTO.recommend())
                    .summaryType(SummaryType.RECOMMENDATION)
                    .build();

            summaryRepository.save(recommend);

            // 업데이트 알람 생성
            alarmService.generateAlarm(userId, RECOMMENDATION_ALARM, AlarmType.UPDATE);

            System.out.println(recommendationDTO.recommend());
        }
    }

    private LogDTO.SummaryResponseDTO fetchLogSummary(String containerName) {
        StringBuilder requestContentBuilder = new StringBuilder();

        // 로그 메시지는 ElasticSearch에서 조회
        String logMessage = logService.getLogWithin30Minutes(containerName);

        // 검색 결과가 빈 값이면 null을 반환
        if (logMessage.isBlank()) {
            return null;
        }

        requestContentBuilder.append(LOG_DATA_HEADER)
                .append("Application Name: ")
                .append(containerName)
                .append("\n")
                .append("Log Content: ")
                .append(logMessage)
                .append("\n");

        return webClient.post()
                .uri(buildURI(LOG_SUMMERY_URI))
                .bodyValue(new LogDTO.SummaryRequestDTO(requestContentBuilder.toString()))
                .retrieve()
                .bodyToMono(LogDTO.SummaryResponseDTO.class)
                .block();
    }

    private LogDTO.PerformanceSummaryResponseDTO fetchMetricSummary(Long sshInfoId){
        StringBuilder requestContentBuilder = new StringBuilder();

        // 1시간 전까지의 성능 지표
        MetricResponse.FindMetricHistoryDTO metricHistory = metricService.findMetricHistory(METRIC_HISTORY_PREVIOUS_HOUR, sshInfoId);

        requestContentBuilder.append(PERFORMANCE_SUMMARY_HEADER);

        // CPU 메트릭 정보를 추가
        requestContentBuilder.append("1. CPU Metrics:\n");
        for (MetricResponse.CpuMetricDTO cpuMetric : metricHistory.cpuMetrics()) {
            requestContentBuilder.append(String.format("- Time: %s, CPU Usage: %.2f%%\n", cpuMetric.time(), cpuMetric.cpuUsage()));
        }

        // 메모리 메트릭 정보를 추가
        requestContentBuilder.append("\n2. Memory Metrics:\n");
        for (MetricResponse.MemoryMetricDTO memoryMetric : metricHistory.memoryMetrics()) {
            requestContentBuilder.append(String.format("- Time: %s, Memory Usage: %.2f MB\n", memoryMetric.time(), memoryMetric.memoryUsage()));
        }

        // 네트워크 In 메트릭 정보를 추가
        requestContentBuilder.append("\n3. Network In Metrics:\n");
        for (MetricResponse.NetworkInMetricDTO networkInMetric : metricHistory.networkInMetrics()) {
            requestContentBuilder.append(String.format("- Time: %s, Network Received: %.2f MB\n", networkInMetric.time(), networkInMetric.networkReceived()));
        }

        // 네트워크 Out 메트릭 정보를 추가
        requestContentBuilder.append("\n4. Network Out Metrics:\n");
        for (MetricResponse.NetworkOutMetricDTO networkOutMetric : metricHistory.networkOutMetrics()) {
            requestContentBuilder.append(String.format("- Time: %s, Network Sent: %.2f MB\n", networkOutMetric.time(), networkOutMetric.networkSent()));
        }

        return webClient.post()
                .uri(buildURI(PERFORMANCE_SUMMERY_URI))
                .bodyValue(new LogDTO.SummaryRequestDTO(requestContentBuilder.toString()))
                .retrieve()
                .bodyToMono(LogDTO.PerformanceSummaryResponseDTO.class)
                .block();
    }

    private LogDTO.HourlySummaryResponseDTO fetchHourlySummary(Long userId) {
        StringBuilder requestContentBuilder = new StringBuilder();

        // 성능 요약 리스트와 어플리케이션 요약 리스트
        LocalDateTime startOfHour = LocalDateTime.now().withMinute(0).minusSeconds(0);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfHour);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfHour);

        // 성능 요약 추가
        appendSummary(requestContentBuilder, PERFORMANCE_SUMMARY_HEADER, performanceSummaries);

        // 일반 및 이상 로그 요약 추가
        appendSummaryWithApplicationName(requestContentBuilder, APPLICATION_LOG_SUMMARY_HEADER, logSummaries);

        return webClient.post()
                .uri(buildURI(HOURLY_SUMMARY_URI))
                .bodyValue(new LogDTO.SummaryRequestDTO(requestContentBuilder.toString()))
                .retrieve()
                .bodyToMono(LogDTO.HourlySummaryResponseDTO.class)
                .block();
    }

    private LogDTO.DailySummaryResponseDTO fetchDailySummary(Long userId) {
        StringBuilder requestContentBuilder = new StringBuilder();

        // 성능 요약 리스트와 어플리케이션 요약 리스트
        LocalDateTime startOfDay = LocalDateTime.now().with(LocalTime.MIN);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfDay);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfDay);

        // 성능 요약 추가
        appendSummary(requestContentBuilder, PERFORMANCE_SUMMARY_HEADER, performanceSummaries);

        // 일반 및 이상 로그 요약 추가
        appendSummaryWithApplicationName(requestContentBuilder, APPLICATION_LOG_SUMMARY_HEADER, logSummaries);

        // LLM에 전달하기 위해 FastAPI에 요청
        return webClient.post()
                .uri(buildURI(DAILY_SUMMERY_URI))
                .bodyValue(new LogDTO.SummaryRequestDTO(requestContentBuilder.toString()))
                .retrieve()
                .bodyToMono(LogDTO.DailySummaryResponseDTO.class)
                .block();
    }

    private LogDTO.TrendSummaryResponseDTO fetchTrendSummary(Long userId){
        StringBuilder requestContentBuilder = new StringBuilder();

        // 1주일 전까지의 일일 리포트를 인풋으로 사용
        LocalDateTime startOfDay = LocalDateTime.now().minusWeeks(1).with(LocalTime.MIN);
        List<Summary> dailySummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.DAILY), userId, startOfDay);

        appendSummary(requestContentBuilder, WEEKLY_TREND_HEADER, dailySummaries);

        return webClient.post()
                .uri(buildURI(TREND_SUMMERY_URI))
                .bodyValue(new LogDTO.SummaryRequestDTO(requestContentBuilder.toString()))
                .retrieve()
                .bodyToMono(LogDTO.TrendSummaryResponseDTO.class)
                .block();
    }

    private LogDTO.RecommendationDTO fetchRecommendation(Long userId) {
        StringBuilder requestContentBuilder = new StringBuilder();

        // 6시간 전의 요약들을 인풋으로
        LocalDateTime startOfTime = LocalDateTime.now().minusHours(6);
        List<Summary> performanceSummaries = summaryRepository.findByTypeWithinDate(List.of(SummaryType.PERFORMANCE), userId, startOfTime);
        List<Summary> logSummaries = summaryRepository.findByTypeWithinDateWithProject(List.of(SummaryType.LOG), userId, startOfTime);

        // 성능 요약 추가
        appendSummary(requestContentBuilder, PERFORMANCE_SUMMARY_HEADER, performanceSummaries);

        // 일반 및 이상 로그 요약 추가
        appendSummaryWithApplicationName(requestContentBuilder, APPLICATION_LOG_SUMMARY_HEADER, logSummaries);

        // LLM에 전달하기 위해 FastAPI에 요청
        return webClient.post()
                .uri(buildURI(RECOMMEND_URI))
                .bodyValue(new LogDTO.SummaryRequestDTO(requestContentBuilder.toString()))
                .retrieve()
                .bodyToMono(LogDTO.RecommendationDTO.class)
                .block();
    }

    private void appendSummary(StringBuilder requestContentBuilder, String header, List<Summary> summaries) {
        requestContentBuilder.append(header);

        if (summaries.isEmpty()) {
            requestContentBuilder.append(NO_SUMMARY_DATA);
        } else {
            for (Summary summary : summaries) {
                requestContentBuilder.append("Summary Date: ")
                        .append(formatLocalDateTime(summary.getCreatedDate())) // 날짜 형식 변환
                        .append("\n")
                        .append("Summary Content: ")
                        .append(summary.getContent()) // 요약 내용 추가
                        .append("\n");
            }
        }
    }

    private void appendSummaryWithApplicationName(StringBuilder requestContentBuilder, String header, List<Summary> summaries) {
        requestContentBuilder.append(header);

        if (summaries.isEmpty()) {
            requestContentBuilder.append(NO_SUMMARY_DATA);
        } else {
            for (Summary summary : summaries) {
                requestContentBuilder.append("Application Name: ")
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

    private URI buildURI(String uri) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(uri);
        return uriBuilder.build().encode().toUri();
    }

    public static String formatLocalDateTime(LocalDateTime localDateTime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        return localDateTime.format(formatter);
    }
}