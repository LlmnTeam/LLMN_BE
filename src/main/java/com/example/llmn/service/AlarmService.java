package com.example.llmn.service;

import com.example.llmn.controller.DTO.AlarmRequest;
import com.example.llmn.controller.DTO.AlarmResponse;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.domain.Alarm;
import com.example.llmn.domain.AlarmType;
import com.example.llmn.domain.User;
import com.example.llmn.repository.AlarmRepository;
import com.example.llmn.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AlarmService {

    private final AlarmRepository alarmRepository;
    private final UserRepository userRepository;

    @Transactional
    public void generateAlarm(Long receiverId, String content, AlarmType alarmType){
        User receiver = userRepository.findById(receiverId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        if(receiver.doesNotReceivingAlarm()){
            return;
        }

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
                        alarm.getAlarmType(),
                        alarm.isRead() ))
                .collect(Collectors.toList());

        return new AlarmResponse.FindAlarmListDTO(alarmDTOS);
    }

    @Transactional
    public void readAlarm(AlarmRequest.ReadAlarmDTO readAlarmDTO, Long userId) {
        LocalDateTime now = LocalDateTime.now();

        List<Long> alarmIds = readAlarmDTO.alarmIds();
        List<Alarm> alarms = alarmRepository.findByIdsWithUser(alarmIds);

        for (Alarm alarm : alarms) {
            // 권한 없음
            if (!alarm.getReceiver().getId().equals(userId)) {
                continue;
            }

            // 현재 시간을 기준으로 읽었다고 업데이트
            alarm.updateIsRead(true, now);
        }
    }

    @Transactional
    @Scheduled(cron = "0 5 0 * * *")
    public void deleteReadAlarm(){
        // 일주일이 지난 이미 읽은 알람들
        LocalDateTime previousDate = LocalDateTime.now().minusDays(3);
        List<Alarm> readAlarm = alarmRepository.findReadBeforeDate(previousDate);

        alarmRepository.deleteAll(readAlarm);
    }
}