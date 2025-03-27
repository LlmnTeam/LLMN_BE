package com.example.llmn.domain.alarm.model.request;

import java.util.List;

public record ReadAlarmReq(List<Long> alarmIds) {
}
