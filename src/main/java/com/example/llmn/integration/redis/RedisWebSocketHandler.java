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

@Slf4j
public class RedisWebSocketHandler extends TextWebSocketHandler implements DisposableBean {

    @Value("${spring.data.redis.host}")
    private String redisHost;
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread redisSubscriberThread;
    private RedisMessageSubscriber subscriber;

    private static final String REDIS_CHANNEL = RedisConstants.REDIS_CHANNEL_SSH;
    private static final int REDIS_TIMEOUT = RedisConstants.REDIS_TIMEOUT_SSH;
    private static final int REDIS_PORT = 6379;
    private static final int MAX_RETRY_ATTEMPTS = 10;
    private static final long INITIAL_RETRY_INTERVAL = 1000; // 1초
    private static final long MAX_RETRY_INTERVAL = 30000; // 30초

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
        log.info("Redis WebSocket Handler 종료됨");
    }

    private void startRedisSubscription() {
        subscriber = new RedisMessageSubscriber();
        redisSubscriberThread = new Thread(this::connectToRedisWithRetry);
        redisSubscriberThread.setDaemon(true);
        redisSubscriberThread.start();
    }

    private void connectToRedisWithRetry() {
        int attempts = 0;
        long retryInterval = INITIAL_RETRY_INTERVAL;

        while (running.get()) {
            try {
                log.info("Redis 연결 시도 중... (시도 {}/{})", attempts + 1, MAX_RETRY_ATTEMPTS);
                subscribeToRedis();
                return; // 연결 성공시 종료
            } catch (JedisConnectionException e) {
                attempts++;
                if (attempts >= MAX_RETRY_ATTEMPTS) {
                    log.error("최대 재시도 횟수 초과. Redis 연결 시도 중단");
                    return;
                }

                try {
                    Thread.sleep(retryInterval);
                    retryInterval = Math.min(retryInterval * 2, MAX_RETRY_INTERVAL); // 지수 백오프 적용 (최대 30초까지)
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void subscribeToRedis() {
        try (Jedis jedis = new Jedis(redisHost, REDIS_PORT, REDIS_TIMEOUT)) {
            log.info("Redis 서버에 연결됨: {}:{}", redisHost, REDIS_PORT);
            jedis.ping(); // 연결 테스트
            jedis.subscribe(subscriber, REDIS_CHANNEL);
        } catch (Exception e) {
            log.error("Redis 구독 중 오류 발생: {}", e.getMessage());
            if (running.get()) {
                log.info("Redis 재연결 시도 중...");
                try {
                    Thread.sleep(INITIAL_RETRY_INTERVAL); // 약간의 지연 후 재연결 시도
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                connectToRedisWithRetry();
            }
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