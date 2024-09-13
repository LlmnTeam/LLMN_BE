package com.example.llmn.service;

import com.example.llmn.controller.DTO.LogDTO;
import com.example.llmn.core.config.LogWebSocketHandler;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class LlmService {

    private final LogService logService;
    private final LogWebSocketHandler logWebSocketHandler;
    private final WebClient webClient;
    private static final String REQUEST_SUMMERY_URI = "http://localhost:8000/process_logs";

    // 주기적으로 로그를 가져와 특정 서비스의 로그만 전송
    @Scheduled(fixedRate = 50000)
    public void streamLogs() {
        String serviceName = "spring";
        String logMessage = "Spring 서비스의 로그 메시지";

        // WebSocket을 통해 서비스 로그 전송
        logWebSocketHandler.sendLog(serviceName, logMessage);
    }

    public LogDTO.SummaryResponseDTO fetchLogSummary(Instant startTime, Instant endTime, String serviceName) {
        // 로그 메시지는 ElasticSearch에서 가져온다
        String logMessage = logService.searchLogInStr(startTime, endTime, serviceName);

        // 검색 결과가 빈 값이면 null을 반환
        if (logMessage == null || logMessage.isEmpty()) {
            return null;
        }

        return webClient.post()
                .uri(buildURI(REQUEST_SUMMERY_URI))
                .bodyValue(new LogDTO.SummaryRequestDTO(logMessage))
                .retrieve()
                .bodyToMono(LogDTO.SummaryResponseDTO.class)
                .block();
    }

    private URI buildURI(String uri) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(uri);
        return uriBuilder.build().encode().toUri();
    }
}
