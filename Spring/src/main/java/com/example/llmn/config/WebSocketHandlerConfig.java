package com.example.llmn.config;

import com.example.llmn.integration.redis.RedisWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebSocketHandlerConfig {

    @Bean
    public RedisWebSocketHandler redisWebSocketHandler() {
        return new RedisWebSocketHandler();
    }
}