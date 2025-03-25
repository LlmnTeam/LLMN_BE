package com.example.llmn.domain.alarm;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.user.User;
import com.example.llmn.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlarmService {

    private final AlarmRepository alarmRepository;
    private final UserRepository userRepository;

    @Transactional
    public void generateAlarm(Long receiverId, String content, AlarmType alarmType){
        User receiver = userRepository.findById(receiverId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        if(receiver.doesNotReceivingAlarm()) return;

        Alarm alarm = Alarm.builder()
                .receiver(receiver)
                .content(content)
                .alarmType(alarmType)
                .build();
        alarmRepository.save(alarm);
    }

    public AlarmResponse.FindAlarmListDTO findAlarmList(Long userId){
        List<Alarm> alarms = alarmRepository.findByReceiverId(userId);
        List<AlarmResponse.AlarmDTO> alarmDTOS = alarms.stream()
                .map(alarm -> new AlarmResponse.AlarmDTO(
                        alarm.getId(),
                        alarm.getContent(),
                        alarm.getReadDate(),
                        alarm.getAlarmType(),
                        alarm.isRead() ))
                .toList();

        return new AlarmResponse.FindAlarmListDTO(alarmDTOS);
    }

    @Transactional
    public void readAlarm(AlarmRequest.ReadAlarmDTO readAlarmDTO, Long userId) {
        List<Long> alarmIds = readAlarmDTO.alarmIds();
        List<Alarm> alarms = alarmRepository.findByIdsWithUser(alarmIds);

        for (Alarm alarm : alarms) {
            if (alarm.isNotOwnedBy(userId)) continue;
            alarm.updateIsRead(true, LocalDateTime.now());
        }
    }

    @Transactional
    @Scheduled(cron = "0 5 0 * * *")
    public void deleteReadAlarm(){
        LocalDateTime previousDate = LocalDateTime.now().minusDays(3);
        List<Alarm> readAlarm = alarmRepository.findReadBeforeDate(previousDate);

        alarmRepository.deleteAll(readAlarm);
    }
}