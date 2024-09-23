package com.example.llmn.service;

import com.example.llmn.controller.DTO.AlarmResponse;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.domain.Alarm;
import com.example.llmn.domain.AlarmType;
import com.example.llmn.domain.User;
import com.example.llmn.repository.AlarmRepository;
import com.example.llmn.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AlarmService {

    private final AlarmRepository alarmRepository;
    private final UserRepository userRepository;

    @Transactional
    public void generateAlarm(Long receiverId, String content, AlarmType alarmType){
        User receiver = userRepository.getReferenceById(receiverId);
        Alarm alarm = Alarm.builder()
                .receiver(receiver)
                .content(content)
                .alarmType(alarmType)
                .build();

        alarmRepository.save(alarm);
    }

    @Transactional(readOnly = true)
    public AlarmResponse.FindAlarmListDTO findAlarmList(Long userId){
        List<Alarm> alarms = alarmRepository.findByReceiverId(userId);

        List<AlarmResponse.AlarmDTO> alarmDTOS = alarms.stream()
                .map(alarm -> new AlarmResponse.AlarmDTO(
                        alarm.getId(),
                        alarm.getContent(),
                        alarm.getReadDate(),
                        alarm.isRead() ))
                .collect(Collectors.toList());

        return new AlarmResponse.FindAlarmListDTO(alarmDTOS);
    }
}