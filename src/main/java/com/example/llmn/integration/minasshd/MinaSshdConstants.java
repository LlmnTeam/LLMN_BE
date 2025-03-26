package com.example.llmn.integration.minasshd;

public class MinaSshdConstants {

    private MinaSshdConstants() {}

    public static final int AUTH_TIMEOUT = 10;
    public static final int CONNECTION_TIMEOUT = 5;
    public static final int SHELL_CHANNEL_TIMEOUT = 10;

    public static final int PTY_COLUMNS = 160;
    public static final int PTY_LINES = 24;
    public static final int PTY_WIDTH = 640;
    public static final int PTY_HEIGHT = 480;
    public static final String PTY_TYPE = "xterm";

    public static final int BUFFER_SIZE = 4096;
    public static final int SLEEP_DURATION_MS = 100;

    public static final String PROMPT_UBUNTU = "ubuntu@";
    public static final String PROMPT_DOLLAR = "$ ";

    public static final int SSH_PORT = 22;

    public static final int ASCII_SIGINT_SIGNAL = 3; // ASCII 0x03은 SIGINT 신호
}
