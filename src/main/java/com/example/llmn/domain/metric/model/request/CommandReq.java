package com.example.llmn.domain.metric.model.request;

public record CommandReq(
        String privateKeyPath,
        String host,
        String username,
        String command) {
}
