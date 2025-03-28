package com.example.llmn.domain.log;

public class LogConstants {

    private LogConstants() {}

    public static final String LOG_FORMAT = "[%s]\n%s";

    public static final String LOGS_DIRECTORY = "logs";
    public static final String LOG_FILE_NAME_SUFFIX = "-log";
    public static final String PROJECT_LOG_URI_TEMPLATE = "/project/%d/%s";

    public static final String LOG_LEVEL_INFO = "INFO";
    public static final String LOG_LEVEL_WARN = "WARN";
    public static final String LOG_LEVEL_ERROR = "ERROR";
    public static final String LOG_LEVEL_UNKNOWN = "UNKNOWN";

    public static final int MAX_LOG_RECORDS_PER_QUERY = 1000;

    public static final String PERFORMANCE_SUMMARY_UPDATE_MESSAGE = "새로운 성능 요약이 생성 되었습니다.";
    public static final String HOURLY_SUMMARY_UPDATE_MESSAGE = "새로운 시간별 요약이 생성 되었습니다.";
    public static final String DAILY_SUMMARY_UPDATE_MESSAGE = "새로운 일일 요약이 생성 되었습니다.";
    public static final String TREND_SUMMARY_UPDATE_MESSAGE = "장기 트렌드 분석 요약이 생성 되었습니다.";
    public static final String RECOMMENDATION_UPDATE_MESSAGE = "새로운 추천 사항이 업데이트 되었습니다";
    public static final String EMPTY_SUMMARY_DATA_MESSAGE = "- 요약 데이터가 존재하지 않습니다.\n";
    public static final String EMPTY_LOG_RECORD_MESSAGE = "로그 기록이 존재하지 않습니다.";

    public static final String LOG_EMERGENCY_ALARM_SUFFIX = "의 로그를 점검 해보세요. 문제점이 발견되었습니다.";
    public static final String LOG_UPDATE_ALARM_SUFFIX = "의 요약이 업데이트 되었습니다.";

    public static final String ELASTICSEARCH_LOG_INDEX_PATTERN = "docker-logs-*";
    public static final String ELASTICSEARCH_LOG_INDEX_PREFIX = "docker-logs-";

    public static final String LOG_FILE_CONTENT_REGEX = "(?=\\[\\d{4}-\\d{2}-\\d{2}_\\d{2}:\\d{2}\\])";

    public static final String LOG_DATA_SECTION_HEADER = "<Log Data>\n";
    public static final String PERFORMANCE_SUMMARY_SECTION_HEADER = "<Performance Summary>\n";
    public static final String APPLICATION_LOG_SUMMARY_SECTION_HEADER = "<Application Log Summary>\n";
    public static final String WEEKLY_TREND_SECTION_HEADER = "-<Weekly Trend Summaries>\n";
}
