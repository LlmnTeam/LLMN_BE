package com.example.llmn.integration.redis;

public class RedisConstants {

    private RedisConstants() {
    }

    // 연결
    public static final int REDIS_PORT = 6379;
    public static final String REDIS_HOST = "redis";
    public static final int REDIS_TIMEOUT_MS = 60000;

    // 레디스 키
    public static final String REDIS_KEY_REFRESH_TOKEN = "refreshToken";
    public static final String REDIS_KEY_SESSION_ID = "sessionId";
    public static final String REDIS_KEY_SSH = "SSH";
    public static final String REDIS_KEY_CODE_TO_EMAIL = "codeToEmail";
    public static final String REDIS_KEY_RESOURCE = "resource";
    public static final String REDIS_KEY_METRIC = "metric";

    public static final String REDIS_KEY_NETWORK_REC = "network:received";
    public static final String REDIS_KEY_NETWORK_TRANS = "network:transmitted";
    public static final String REDIS_KEY_NETWORK_RX = "networkRx";
    public static final String REDIS_KEY_NETWORK_TX = "networkTx";
    public static final String REDIS_KEY_NETWORK_TIME = "networkTime";

    // 만료 시간
    public static final Long REDIS_EXPIRY_VERIFICATION_CODE_MS = 175 * 1000L;  // 회원 가입 코드 유효시간
    public static final Long REDIS_EXPIRY_METRIC_MS = 60 * 1000L; // 1분
    public static final Long REDIS_EXPIRY_RESOURCE_MS = 10 * 60 * 1000L; // 10분
    public static final Long REDIS_EXPIRY_SSH_MS = 60L * 60 * 24 * 30; // 30일

    // 재시도
    public static final int REDIS_RETRY_MAX_ATTEMPTS = 10;
    public static final int REDIS_RETRY_MAX_PUBLISH_ATTEMPTS = 3; // 재시도 횟수 줄임
    public static final long REDIS_RETRY_DELAY_MS = 200; // 고정 지연 시간
    public static final long REDIS_RETRY_INITIAL_INTERVAL_MS = 1000; // 1초
    public static final long REDIS_RETRY_MAX_INTERVAL_MS = 30000; // 30초

    public static final String REDIS_CHANNEL_SSH = "ssh-command-output";
}
