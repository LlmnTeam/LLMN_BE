package com.example.llmn.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class LogWebSocketHandler extends TextWebSocketHandler {

    // 각 클라이언트의 세션
    private Map<WebSocketSession, String> sessionServiceMap = new ConcurrentHashMap<>();

    // 클라이언트가 구독할 때
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
                    session.sendMessage(new TextMessage(logMessage));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // 구독자가 있는지 확인
    public boolean hasSubscribers(String serviceName) {
        return sessionServiceMap.containsValue(serviceName);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionServiceMap.remove(session);  // 연결이 종료되면 구독 상태 제거
    }
}
