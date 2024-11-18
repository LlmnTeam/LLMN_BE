package com.example.llmn.core.utils;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public class DateTimeUtils {
    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter LOG_FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH");
    private static final DateTimeFormatter SIMPLE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    public static String formatDate(Date date, String format) {
        return new SimpleDateFormat(format).format(date);
    }

    public static String formatLocalDateTime(LocalDateTime localDateTime) {
        if(localDateTime == null){
            return null;
        }

        return localDateTime.format(DEFAULT_FORMATTER);
    }

    public static String getTodayDateInString() {
        return LocalDate.now().format(SIMPLE_DATE_FORMATTER);
    }

    public static LocalDateTime getThirtyMinutesAgoTime(){
        return LocalDateTime.now().minus(30, ChronoUnit.MINUTES);
    }

    public static LocalDateTime parseDateTimeFromFile(String file) {
        // 파일 이름에서 "log-" 뒤부터 ".txt" 앞까지의 부분 추출
        String dateTimePart = file.substring(file.indexOf("log-") + 4, file.lastIndexOf("-"));
        return LocalDateTime.parse(dateTimePart, LOG_FILE_FORMATTER);
    }
}
