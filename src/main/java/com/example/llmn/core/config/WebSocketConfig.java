package com.example.llmn.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RedisWebSocketHandler redisWebSocketHandler;

    public WebSocketConfig(RedisWebSocketHandler redisWebSocketHandler) {
        this.redisWebSocketHandler = redisWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // WebSocket 핸들러 등록
        registry.addHandler(redisWebSocketHandler, "/ws").setAllowedOrigins("*");
    }
}