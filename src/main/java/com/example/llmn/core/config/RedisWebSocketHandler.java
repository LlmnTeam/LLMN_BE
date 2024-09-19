package com.example.llmn.core.config;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.io.IOException;

public class RedisWebSocketHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String redisChannel = "ssh-command-output"; // 고정된 채널 이름 사용

        Jedis jedis = new Jedis("localhost"); // Redis 서버에 연결

        // Redis 채널 구독
        new Thread(() -> {
            jedis.subscribe(new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    try {
                        // Redis에서 받은 메시지를 WebSocket으로 클라이언트에게 전송
                        session.sendMessage(new TextMessage(message));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }, redisChannel); // 고정된 채널 구독
        }).start();

        System.out.println("WebSocket 연결됨: " + session.getId() + ", 구독 채널: " + redisChannel);
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 클라이언트로부터 받은 메시지를 Redis에 전송
        Jedis jedis = new Jedis("localhost");
        jedis.publish("your-redis-channel", message.getPayload());
        System.out.println("Redis에 메시지 전송: " + message.getPayload());
    }
}