package com.example.llmn.integration.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.example.llmn.integration.redis.RedisConstants.*;

@Slf4j
public class RedisWebSocketHandler extends TextWebSocketHandler implements DisposableBean {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    private Thread redisSubscriberThread;
    private RedisMessageSubscriber subscriber;

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

    @Override
    public void destroy() {
        running.set(false);
        if (subscriber != null) {
            subscriber.unsubscribe();
        }
        if (redisSubscriberThread != null) {
            redisSubscriberThread.interrupt();
        }
    }

    private void startRedisSubscription() {
        subscriber = new RedisMessageSubscriber();
        redisSubscriberThread = new Thread(this::connectToRedisWithRetry);
        redisSubscriberThread.setDaemon(true);
        redisSubscriberThread.start();
    }

    private void connectToRedisWithRetry() {
        int attempts = 0;
        long retryInterval = REDIS_RETRY_INITIAL_INTERVAL_MS;

        while (running.get()) {
            try {
                log.info("Redis 연결 시도 중... (시도 {}/{})", attempts + 1, REDIS_RETRY_MAX_ATTEMPTS);
                subscribeToRedis();
                return;
            } catch (JedisConnectionException e) {
                attempts++;
                log.error("Redis 연결 실패: {}", e.getMessage());

                if (attempts >= REDIS_RETRY_MAX_ATTEMPTS)
                    break;

                try {
                    Thread.sleep(retryInterval);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }

                retryInterval = Math.min(retryInterval * 2, REDIS_RETRY_MAX_INTERVAL_MS); // 지수 백오프 적용
            }
        }
    }

    private void subscribeToRedis() {
        try (Jedis jedis = new Jedis(redisHost, REDIS_PORT, REDIS_TIMEOUT_MS)) {
            log.info("Redis 서버에 연결됨: {}:{}", redisHost, REDIS_PORT);
            jedis.ping(); // 연결 테스트
            jedis.subscribe(subscriber, REDIS_CHANNEL_SSH);
        } catch (JedisConnectionException e) {
            log.error("Redis 구독 중 오류 발생: {}", e.getMessage());
            throw e;
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