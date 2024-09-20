package com.example.llmn.core.config;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.io.IOException;

public class RedisWebSocketHandler extends TextWebSocketHandler {

    // 클라이언트가 WebSocket 연결을 성립하면 호출
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String redisChannel = "ssh-command-output";

        Jedis jedis = new Jedis("localhost", 6379); // Redis 서버에 연결

        // Redis의 구독 기능이 블로킹 호출이기 때문에 메인 스레드가 중단되지 않도록 하기 위해 새로운 스레드로 선언
        new Thread(() -> {
            jedis.subscribe(new JedisPubSub() { // Redis 채널 구독
                @Override
                public void onMessage(String channel, String message) {
                    try {
                        session.sendMessage(new TextMessage(message)); // Redis에서 받은 메시지를 WebSocket으로 클라이언트에게 전송
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }, redisChannel); // 고정된 채널 구독
        }).start();

        System.out.println("WebSocket 연결됨: " + session.getId() + ", 구독 채널: " + redisChannel);
    }

    // 클라이언트가 WebSocket을 통해 서버로 메시지를 보낼 때 호출
    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 클라이언트로부터 받은 메시지를 Redis에 전송
        Jedis jedis = new Jedis("localhost", 6379);
        jedis.publish("your-redis-channel", message.getPayload());
        System.out.println("Redis에 메시지 전송: " + message.getPayload());
    }
}