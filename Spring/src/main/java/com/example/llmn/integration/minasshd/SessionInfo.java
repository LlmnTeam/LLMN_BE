package com.example.llmn.integration.minasshd;

import java.time.LocalDateTime;

public class SessionInfo {

    public final Long userId;
    public final SecureShellClient client;
    public final LocalDateTime creationTime;
    public volatile LocalDateTime lastAccessTime;
    public volatile int commandCount;

    public SessionInfo(Long userId, SecureShellClient client) {
        this.userId = userId;
        this.client = client;
        this.creationTime = LocalDateTime.now();
        this.lastAccessTime = LocalDateTime.now();
        this.commandCount = 0;
    }

    public void updateAccessTime() {
        this.lastAccessTime = LocalDateTime.now();
    }

    public void incrementCommandCount() {
        this.commandCount++;
    }

    public long getIdleTimeMillis() {
        return java.time.Duration.between(lastAccessTime, LocalDateTime.now()).toMillis();
    }
}
