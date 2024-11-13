package com.example.llmn.core.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DateTimeUtils {
    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static String formatLocalDateTime(LocalDateTime localDateTime) {
        if(localDateTime == null){
            return null;
        }

        return localDateTime.format(DEFAULT_FORMATTER);
    }
}
