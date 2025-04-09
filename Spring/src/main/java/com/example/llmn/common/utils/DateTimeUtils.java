package com.example.llmn.common.utils;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public class DateTimeUtils {

    private DateTimeUtils() {
    }

    public static final String LOG_TITLE_FORMAT = "yyyy-MM-dd_HH";
    public static final String LOG_TEXT_FORMAT = "yyyy-MM-dd_HH:mm";
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    public static final DateTimeFormatter LOG_FILE_FORMATTER = DateTimeFormatter.ofPattern(LOG_TITLE_FORMAT);
    public static final DateTimeFormatter LOG_TEXT_FORMATTER = DateTimeFormatter.ofPattern(LOG_TEXT_FORMAT);
    public static final DateTimeFormatter SIMPLE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    public static final DateTimeFormatter HOUR_MINUTE_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public static String formatDate(Date date, String format) {
        return new SimpleDateFormat(format).format(date);
    }

    public static String formatLocalDateTime(LocalDateTime localDateTime, DateTimeFormatter formatter) {
        return localDateTime.format(formatter);
    }

    public static String formatLocalDateTime(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }

        return localDateTime.format(DATE_TIME_FORMATTER);
    }

    public static Instant parseInstant(String timestamp) {
        return timestamp != null ? Instant.parse(timestamp) : null;
    }

    public static LocalDateTime parseDateTimeFromLogFile(String file) {
        // 파일 이름에서 "log-" 뒤부터 ".txt" 앞까지의 부분 추출
        String dateTimePart = file.substring(file.indexOf("log-") + 4, file.lastIndexOf("-"));
        return LocalDateTime.parse(dateTimePart, LOG_FILE_FORMATTER);
    }

    public static String getTodayDateInString() {
        return LocalDate.now().format(SIMPLE_DATE_FORMATTER);
    }

    public static LocalDateTime getThirtyMinutesAgoTime() {
        return LocalDateTime.now().minus(30, ChronoUnit.MINUTES);
    }

    public static LocalDateTime getCurrentHourStartMinusHours(int minusHour) {
        return LocalDateTime.now()
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
