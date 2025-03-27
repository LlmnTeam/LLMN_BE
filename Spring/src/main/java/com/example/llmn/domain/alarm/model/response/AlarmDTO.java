package com.example.llmn.domain.alarm.model.response;

import com.example.llmn.domain.alarm.Alarm;
import com.example.llmn.domain.alarm.AlarmType;

import java.time.LocalDateTime;

public record AlarmDTO(
        Long id,
        String content,
        LocalDateTime generatedDate,
        AlarmType type,
        boolean isRead) {

    public static AlarmDTO from(Alarm alarm) {
        return new AlarmDTO(
                alarm.getId(),
                alarm.getContent(),
                alarm.getReadDate(),
                alarm.getAlarmType(),
                alarm.isRead()
        );
    }
}
