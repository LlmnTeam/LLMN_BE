package com.example.llmn.domain.alarm.model.response;

import com.example.llmn.domain.alarm.Alarm;

import java.util.List;

public record FindAlarmListRes(List<AlarmDTO> alarms) {

    public static FindAlarmListRes from(List<Alarm> alarms) {
        List<AlarmDTO> alarmDTOS = alarms.stream()
                .map(AlarmDTO::from)
                .toList();
        return new FindAlarmListRes(alarmDTOS);
    }
}
