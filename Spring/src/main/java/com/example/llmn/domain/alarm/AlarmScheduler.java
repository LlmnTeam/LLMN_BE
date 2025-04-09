package com.example.llmn.domain.alarm;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AlarmScheduler {

    private final AlarmRepository alarmRepository;

    @Transactional
    @Scheduled(cron = "0 5 0 * * *")
    public void deleteReadAlarm() {
        LocalDateTime previousDate = LocalDateTime.now().minusDays(3);
        List<Alarm> readAlarm = alarmRepository.findReadBeforeDate(previousDate);
        alarmRepository.deleteAll(readAlarm);
    }

    @Transactional
    @Scheduled(cron = "0 20 0 * * *")
    public void deleteUnreadAlarm() {
        LocalDateTime previousDate = LocalDateTime.now().minusDays(7);
        List<Alarm> unreadAlam = alarmRepository.findUnreadBeforeDate(previousDate);
        alarmRepository.deleteAll(unreadAlam);
    }
}