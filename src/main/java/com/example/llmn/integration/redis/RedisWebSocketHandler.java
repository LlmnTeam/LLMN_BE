package com.example.llmn.integration.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class RedisWebSocketHandler extends TextWebSocketHandler {

    @Value("${spring.data.redis.host}")
    private String redisHost;
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    private static final String REDIS_CHANNEL = "ssh-command-output";
    private static final int REDIS_TIMEOUT = 60000; // 1분
    private static final int REDIS_PORT = 6379;

    public RedisWebSocketHandler() {
        startRedisSubscription();
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket 연결됨: {}, 총 연결된 세션 수: {}", session.getId(), sessions.size());
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WebSocket 연결 해제됨: {}, 남은 세션 수: {}", session.getId(), sessions.size());
    }

    private void startRedisSubscription() {
        Thread redisSubscriberThread = new Thread(this::subscribeToRedis);
        redisSubscriberThread.setDaemon(true); // 애플리케이션 종료 시 자동으로 종료
        redisSubscriberThread.start();
    }

    private void subscribeToRedis() {
        try (Jedis jedis = new Jedis(redisHost, REDIS_PORT, REDIS_TIMEOUT)) {
            jedis.subscribe(new RedisMessageSubscriber(), REDIS_CHANNEL);
        } catch (Exception e) {
            log.error("Redis 구독 중 오류 발생: {}", e.getMessage());
        }
    }

    private class RedisMessageSubscriber extends JedisPubSub {
        @Override
        public void onMessage(String channel, String message) {
            broadcastToSessions(message);
        }

        private void broadcastToSessions(String message) {
            List<WebSocketSession> closedSessions = new ArrayList<>();
            for (WebSocketSession session : sessions) {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(message));
                    } else {
                        closedSessions.add(session);
                    }
                } catch (IOException e) {
                    log.error("WebSocket 메시지 전송 오류: {}", e.getMessage());
                    closedSessions.add(session);
                }
            }
            sessions.removeAll(closedSessions);
        }
    }
}