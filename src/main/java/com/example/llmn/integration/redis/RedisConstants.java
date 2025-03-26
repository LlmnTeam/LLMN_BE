package com.example.llmn.integration.redis;

public class RedisConstants {

    private RedisConstants() {}

    public static final int REDIS_PORT = 6379;
    public static final String REDIS_HOST = "redis";

    public static final String REDIS_KEY_REFRESH_TOKEN = "refreshToken";
    public static final String REDIS_KEY_ACCESS_TOKEN = "accessToken";
    public static final String REDIS_KEY_SESSION_ID = "sessionId";
    public static final String REDIS_KEY_SSH = "SSH";

    public static final String REDIS_KEY_NETWORK_REC = "network:received";
    public static final String REDIS_KEY_NETWORK_TRANS = "network:transmitted";
    public static final String REDIS_KEY_RESOURCE = "resource";

    public static final Long REDIS_EXP_RESOURCE = 10 * 60 * 1000L; // 10분
    public static final Long REDIS_EXP_SSH = 60L * 60 * 24 * 30; // 30일

    public static final int REDIS_TIMEOUT_SSH = 60000; // 1분
    public static final String REDIS_CHANNEL_SSH = "ssh-command-output"; // 고정된 Redis 채널 이름
}
