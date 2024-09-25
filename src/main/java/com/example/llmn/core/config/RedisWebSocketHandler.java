package com.example.llmn.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class RedisWebSocketHandler extends TextWebSocketHandler {

    @Value("${spring.data.redis.host}")
    private String REDIS_HOST;

    private static final String REDIS_CHANNEL = "ssh-command-output";
    private static final int REDIS_TIMEOUT = 60000; // 1분
    private static final int REDIS_PORT = 6379;

    // 현재 연결된 WebSocket 세션
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    public RedisWebSocketHandler() {
        startRedisSubscription();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket 연결됨: " + session.getId() + ", 총 연결된 세션 수: " + sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WebSocket 연결 해제됨: " + session.getId() + ", 남은 세션 수: " + sessions.size());
    }

    // Redis 구독을 한 번만 수행하고, 메시지를 모든 WebSocket 세션에 전송
    private void startRedisSubscription() {
        new Thread(() -> {
            try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT, REDIS_TIMEOUT)) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        // 모든 연결된 WebSocket 세션에 메시지를 전송
                        for (WebSocketSession session : sessions) {
                            try {
                                if (session.isOpen()) {
                                    session.sendMessage(new TextMessage(message));
                                } else {
                                    sessions.remove(session); // 세션이 닫혔으면 제거
                                }
                            } catch (IOException e) {
                                log.error("WebSocket 메시지 전송 오류: " + e.getMessage());
                                sessions.remove(session); // 전송 실패 시 세션 제거
                            }
                        }
                    }
                }, REDIS_CHANNEL);
            } catch (Exception e) {
                log.error("Redis 구독 중 오류 발생: " + e.getMessage());
            }
        }).start();
    }
}