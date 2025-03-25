package com.example.llmn.domain.log;

public class LogConstants {

    private LogConstants() {}

    public static final String LOGS_DIRECTORY = "logs";

    public static final String LOG_LEVEL_INFO = "INFO";
    public static final String LOG_LEVEL_WARN = "WARN";
    public static final String LOG_LEVEL_ERROR = "ERROR";
    public static final String LOG_LEVEL_UNKNOWN = "UNKNOWN";

    public static final String LOG_KEY_TIMESTAMP = "@timestamp";
    public static final String LOG_KEY_LEVEL = "log_level";
    public static final String LOG_KEY_CONTAINER_NAME = "container_name";
    public static final String LOG_KEY_CONTAINER = "container";
    public static final String LOG_KEY_MESSAGE = "message";
    public static final String LOG_KEY_PROCESSED = "is_processed";
    public static final String LOG_KEY_ID = "_id";

    public static final int MAX_LOG_SIZE = 1000;

    public static final String PERFORMANCE_SUMMARY_ALARM = "새로운 성능 요약이 생성 되었습니다.";
    public static final String HOURLY_SUMMARY_ALARM = "새로운 시간별 요약이 생성 되었습니다.";
    public static final String DAILY_SUMMARY_ALARM = "새로운 일일 요약이 생성 되었습니다.";
    public static final String TREND_SUMMARY_ALARM = "장기 트렌드 분석 요약이 생성 되었습니다.";
    public static final String RECOMMENDATION_ALARM = "새로운 추천 사항이 업데이트 되었습니다";
    public static final String NO_SUMMARY_DATA = "- 요약 데이터가 존재하지 않습니다.\n";
    public static final String NO_LOG_RECORD = "로그 기록이 존재하지 않습니다.";

    public static final String LOG_EMERGENCY_ALARM_SUFFIX = "의 로그를 점검 해보세요. 문제점이 발견되었습니다.";
    public static final String LOG_UPDATE_ALARM_SUFFIX = "의 요약이 업데이트 되었습니다.";

    public static final String LOG_INDEX = "docker-logs-*";
    public static final String LOG_INDEX_PREFIX = "docker-logs-";

    public static final String LOG_FILE_CONTENT_REGEX = "(?=\\[\\d{4}-\\d{2}-\\d{2}_\\d{2}:\\d{2}\\])";

    public static final String LOG_DATA_HEADER = "<Log Data>\n";
    public static final String PERFORMANCE_SUMMARY_HEADER = "<Performance Summary>\n";
    public static final String APPLICATION_LOG_SUMMARY_HEADER = "<Application Log Summary>\n";
    public static final String WEEKLY_TREND_HEADER = "-<Weekly Trend Summaries>\n";
}
