package com.example.llmn.core.config;

import com.example.llmn.domain.LogEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@RequiredArgsConstructor
public class LogWebSocketHandler extends TextWebSocketHandler {

    // 각 클라이언트의 서비스 구독 상태
    private Map<WebSocketSession, String> sessionServiceMap = new ConcurrentHashMap<>();

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        String payload = message.getPayload();

        if (payload.startsWith("subscribe:")) {
            String serviceName = payload.split(":")[1];
            sessionServiceMap.put(session, serviceName);
            session.sendMessage(new TextMessage("Subscribed to: " + serviceName));
        } else if (payload.startsWith("unsubscribe:")) {
            sessionServiceMap.remove(session);
            session.sendMessage(new TextMessage("Unsubscribed"));
        }
    }

    public void sendLog(String serviceName, String logMessage) {
        sessionServiceMap.forEach((session, subscribedService) -> {
            if (subscribedService.equals(serviceName)) {
                try {
                    session.sendMessage(new TextMessage(logMessage));  // 구독된 서비스의 로그만 전송
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionServiceMap.remove(session);
    }
}
