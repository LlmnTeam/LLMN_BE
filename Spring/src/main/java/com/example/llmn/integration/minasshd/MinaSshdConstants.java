package com.example.llmn.integration.minasshd;

public class MinaSshdConstants {

    private MinaSshdConstants() {}

    // 시간 제한 설정 (초 단위)
    public static final int SSH_AUTH_TIMEOUT = 10;
    public static final int SSH_CONNECTION_TIMEOUT = 5;
    public static final int SHELL_OPEN_TIMEOUT = 10;
    public static final int COMMAND_TIMEOUT = 30000;

    public static final int MAX_SESSIONS_PER_USER = 5;
    public static final long SESSION_IDLE_TIMEOUT = 30 * 60 * 1000; // 30분
    public static final int GLOBAL_MAX_SESSIONS = 100;

    // 터미널 설정
    public static final int TERMINAL_COLUMNS = 160;
    public static final int TERMINAL_LINES = 24;
    public static final int TERMINAL_WIDTH_PIXELS = 640;
    public static final int TERMINAL_HEIGHT_PIXELS = 480;
    public static final String TERMINAL_TYPE = "xterm";

    // 버퍼 및 시스템 설정
    public static final int OUTPUT_BUFFER_SIZE = 4096;
    public static final int POLLING_INTERVAL_MS = 100;

    // 쉘 프롬프트 식별자
    public static final String SHELL_PROMPT_UBUNTU = "ubuntu@";
    public static final String SHELL_PROMPT_DOLLAR = "$ ";

    public static final int SSH_PORT = 22;

    // ASCII 0x03은 SIGINT 신호 (제어 신호)
    public static final int CTRL_C_SIGNAL_CODE = 3;

    // 연결 확인 명령어
    public static final String CONNECTION_TEST_COMMAND = "uptime";
    public static final String CONNECTION_TEST_SUCCESS_MARKER = "load average";

    public static final String SSH_KEYS_DIRECTORY = "ssh";

    public static final String FAIL_COMMAND = "명령어 실행에 실패하였습니다.";
    public static final String DISCONNECTED = "연결이 끊어졌습니다. 재연결 시도 후에도 연결할 수 없습니다.";

    public static final int MAX_COMMAND_RETRIES = 2;
    public static final int MAX_RECONNECT_ATTEMPTS = 5;
    public static final long INITIAL_RECONNECT_DELAY = 1000; // 1초
    public static final long MAX_RECONNECT_DELAY = 30000; // 최대 30초 지연
}
