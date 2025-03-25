package com.example.llmn.domain.alarm;

import java.util.List;

public class AlarmRequest {

    public record ReadAlarmDTO(List<Long> alarmIds) {}
}
