package com.example.llmn.service;

import com.example.llmn.core.config.LogWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LlmService {

    private final LogService logService;
    private final LogWebSocketHandler logWebSocketHandler;

    // 주기적으로 로그를 가져와 특정 서비스의 로그만 전송
    @Scheduled(fixedRate = 50000)
    public void streamLogs() {
        String serviceName = "spring";
        String logMessage = "Spring 서비스의 로그 메시지";

        // WebSocket을 통해 서비스 로그 전송
        logWebSocketHandler.sendLog(serviceName, logMessage);
    }
}
