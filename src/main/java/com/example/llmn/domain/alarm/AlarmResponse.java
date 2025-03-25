package com.example.llmn.domain.alarm;

import java.time.LocalDateTime;
import java.util.List;

public class AlarmResponse {

    public record FindAlarmListDTO(List<AlarmDTO> alarms) {}

    public record AlarmDTO(Long id,
                           String content,
                           LocalDateTime generatedDate,
                           AlarmType type,
                           boolean isRead) {}
}
