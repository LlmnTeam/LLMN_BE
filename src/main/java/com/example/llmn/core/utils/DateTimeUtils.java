package com.example.llmn.core.utils;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public class DateTimeUtils {

    private DateTimeUtils() {}

    private static final DateTimeFormatter FORMATTER_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FORMATTER_LOG_FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH");
    private static final DateTimeFormatter FORMATTER_SIMPLE_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    public static final DateTimeFormatter FORMATTER_HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    public static String formatDate(Date date, String format) {
        return new SimpleDateFormat(format).format(date);
    }

    public static String formatLocalDateTime(LocalDateTime localDateTime, DateTimeFormatter formatter){
        return localDateTime.format(formatter);
    }

    public static String formatLocalDateTime(LocalDateTime localDateTime) {
        if(localDateTime == null){
            return null;
        }

        return localDateTime.format(FORMATTER_DATE_TIME);
    }

    public static String getTodayDateInString() {
        return LocalDate.now().format(FORMATTER_SIMPLE_DATE);
    }

    public static LocalDateTime getThirtyMinutesAgoTime(){
        return LocalDateTime.now().minus(30, ChronoUnit.MINUTES);
    }

    public static LocalDateTime parseDateTimeFromLogFile(String file) {
        // 파일 이름에서 "log-" 뒤부터 ".txt" 앞까지의 부분 추출
        String dateTimePart = file.substring(file.indexOf("log-") + 4, file.lastIndexOf("-"));
        return LocalDateTime.parse(dateTimePart, FORMATTER_LOG_FILE);
    }

    public static LocalDateTime getCurrentHourStartMinusHours(int minusHour){
        return  LocalDateTime.now()
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .minusHours(minusHour);
    }

    public static boolean isWithinDateRange(LocalDateTime dateTime, LocalDateTime startDate, LocalDateTime endDate) {
        if (dateTime == null) {
            return false;
        }

        boolean isAfterOrEqualStart = !dateTime.isBefore(startDate);
        boolean isBeforeOrEqualEnd = !dateTime.isAfter(endDate);

        return isAfterOrEqualStart && isBeforeOrEqualEnd;
    }
}
