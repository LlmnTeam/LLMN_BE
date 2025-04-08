package com.example.llmn.integration.minasshd;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.common.utils.KeyPairUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.PtyChannelConfiguration;
import org.apache.sshd.common.channel.PtyMode;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.example.llmn.integration.minasshd.MinaSshdConstants.*;
import static com.example.llmn.integration.redis.RedisConstants.*;

@Slf4j
public class SecureShellClient implements AutoCloseable {

    private SshClient sshClient;
    private ClientSession sshSession;
    private ClientChannel shellChannel;
    private OutputStream shellInputStream;
    private InputStream shellOutputStream;
    private Jedis redisClient;

    private final SshConnectionConfig connectionConfig;
    private final byte[] outputBuffer;

    private static final JedisPool jedisPool = new JedisPool(new JedisPoolConfig(), REDIS_HOST, REDIS_PORT, REDIS_TIMEOUT_SSH);

    public SecureShellClient(String host, String username, String privateKeyPath) {
        this.connectionConfig = new SshConnectionConfig(host, username, privateKeyPath);
        this.outputBuffer = new byte[OUTPUT_BUFFER_SIZE];
        establishConnection();
    }

    private <T> T executeWithRetryAndReconnect(Callable<T> action) throws IOException, InterruptedException {
        if (!isConnected() && !attemptReconnect())
            return null;

        try {
            return action.call();
        } catch (IOException | InterruptedException e) {
            if (!isConnected()) attemptReconnect();
            throw e;
        } catch (Exception e) {
            throw new CustomException(ExceptionCode.SSH_CONNECT_FAIL);
        }
    }

    public String runCommandInInteractiveShell(String command) {
        try {
            String result = executeWithRetryAndReconnect(() -> {
                clearOutputBuffer();
                sendCommandToShell(command);
                return readShellOutput();
            });

            if (result == null)
                return !isConnected() ? DISCONNECTED : FAIL_COMMAND;

            return result;
        } catch (IOException e) {
            return FAIL_COMMAND;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FAIL_COMMAND;
        }
    }

    public String runSingleCommand(String command) {
        try {
            return executeWithRetryAndReconnect(() -> {
                try (ClientChannel channel = sshSession.createExecChannel(command)) {
                    ByteArrayOutputStream commandOutputBuffer = executeAndCaptureCommandOutput(channel);
                    return convertToUtf8String(commandOutputBuffer);
                }
            });
        } catch (IOException | InterruptedException e) {
            throw new CustomException(ExceptionCode.SSH_COMMAND_FAIL);
        }
    }

    public void clearInitialConnectionMessages() {
        try {
            readShellOutput();
        } catch (IOException e) {
            log.error("초기 명령어 flush 실패", e);
        } catch (InterruptedException e) {
            log.error("초기 명령어 flush 중 인터럽트 발생", e);
            Thread.currentThread().interrupt();
        }
    }

    public void sendInterruptSignal() {
        try {
            if (isConnected()) {
                shellInputStream.write(CTRL_C_SIGNAL_CODE);
                shellInputStream.flush();
            } else {
                log.warn("<SSHD> 연결이 끊긴 상태에서 인터럽트 신호 전송 시도");
            }
        } catch (IOException e) {
            log.error("SIGINT 신호 전송 실패: {}", e.getMessage());
        }
    }

    public boolean attemptReconnect() {
        for (int attempt = 0; attempt < MAX_RECONNECT_ATTEMPTS; attempt++) {
            try {
                closeQuietly(); // 기존 자원 정리
                applyReconnectionDelay(attempt);  // 지수 백오프 지연 적용 (첫 시도는 지연 없음)
                establishConnection(); // 연결 재수립
                return true;
            } catch (Exception e) {
                log.error("<SSHD> 연결 재시도 실패: {}", e.getMessage());
            }
        }
        log.error("<SSHD> 재시도 실패: {}", connectionConfig.getHost());
        return false;
    }

    @Override
    public void close() {
        closeQuietly();
    }

    public void closeQuietly() {
        closeResource(shellChannel, "shell channel");
        closeResource(sshSession, "SSH session");
        closeResource(sshClient, "SSH client");
        closeResource(redisClient, "Redis client");
        closeResource(shellInputStream, "input stream");
        closeResource(shellOutputStream, "output stream");

        shellChannel = null;
        sshSession = null;
        sshClient = null;
        redisClient = null;
        shellInputStream = null;
        shellOutputStream = null;
    }

    public boolean isConnected() {
        return sshClient != null && sshClient.isOpen() &&
                sshSession != null && sshSession.isOpen() &&
                shellChannel != null && shellChannel.isOpen();
    }

    private void establishConnection() {
        this.sshClient = createSshClient();
        this.sshSession = establishSessionConnection();
        this.shellChannel = openShellChannel();
        this.redisClient = initializeRedisClient();
        this.shellInputStream = shellChannel.getInvertedIn();
        this.shellOutputStream = shellChannel.getInvertedOut();
    }

    private SshClient createSshClient() {
        SshClient client = SshClient.setUpDefaultClient();
        client.start();
        return client;
    }

    private ClientSession establishSessionConnection() {
        try {
            String privateKeyPath = connectionConfig.getPrivateKeyPath();
            if (privateKeyPath.startsWith("file://"))
                privateKeyPath = Paths.get(URI.create(privateKeyPath)).toString();

            ConnectFuture connectFuture = sshClient.connect(connectionConfig.getUsername(), connectionConfig.getHost(), SSH_PORT);
            ClientSession session = connectFuture.verify(SSH_CONNECTION_TIMEOUT, TimeUnit.SECONDS).getSession();

            KeyPair keyPair = KeyPairUtils.loadKeyPair(privateKeyPath);
            session.addPublicKeyIdentity(keyPair);
            session.auth().verify(SSH_AUTH_TIMEOUT, TimeUnit.SECONDS);

            return session;
        } catch (Exception e) {
            logConnectionFailure(e);
            throw new CustomException(ExceptionCode.SSH_CONNECT_FAIL);
        }
    }

    private ClientChannel openShellChannel() {
        try {
            PtyChannelConfiguration ptyConfig = createTerminalConfiguration();
            ClientChannel channel = sshSession.createShellChannel(ptyConfig, Collections.emptyMap());

            waitForChannelToOpen(channel);
            return channel;
        } catch (IOException e) {
            log.error("Shell 채널 생성 중 오류 발생", e);
            throw new CustomException(ExceptionCode.SHELL_CONNECT_FAIL);
        }
    }

    private PtyChannelConfiguration createTerminalConfiguration() {
        PtyChannelConfiguration ptyConfig = new PtyChannelConfiguration();
        ptyConfig.setPtyType(TERMINAL_TYPE);
        ptyConfig.setPtyColumns(TERMINAL_COLUMNS);
        ptyConfig.setPtyLines(TERMINAL_LINES);
        ptyConfig.setPtyWidth(TERMINAL_WIDTH_PIXELS);
        ptyConfig.setPtyHeight(TERMINAL_HEIGHT_PIXELS);

        Map<PtyMode, Integer> terminalModes = new EnumMap<>(PtyMode.class);
        terminalModes.put(PtyMode.ECHO, 0);
        ptyConfig.setPtyModes(terminalModes);

        return ptyConfig;
    }

    private void waitForChannelToOpen(ClientChannel channel) throws IOException {
        channel.open().verify(SHELL_OPEN_TIMEOUT, TimeUnit.SECONDS);
    }

    private Jedis initializeRedisClient() {
        try {
            return jedisPool.getResource();
        } catch (Exception e) {
            log.error("<SSHD> Redis 연결 실패: {}", e.getMessage());
            return null; // Redis 실패해도 SSH 기능은 계속 동작
        }
    }

    private void clearOutputBuffer() throws IOException {
        if (shellOutputStream.available() > 0) {
            byte[] buffer = new byte[4096];
            while (shellOutputStream.available() > 0) {
                shellOutputStream.read(buffer);
            }
        }
    }

    private void sendCommandToShell(String command) throws IOException {
        byte[] commandBytes = (command + "\n").getBytes(StandardCharsets.UTF_8);
        shellInputStream.write(commandBytes);
        shellInputStream.flush();
    }

    private String readShellOutput() throws IOException, InterruptedException {
        StringBuilder output = new StringBuilder();
        long startTime = System.currentTimeMillis();

        while (isWithinTimeout(startTime)) {
            readAndAppendAvailableOutput(output);
            if (hasOutputAndCommandComplete(output)) {
                return output.toString();
            }

            Thread.sleep(POLLING_INTERVAL_MS); // 정해진 간격만큼 대기 후 다음 루프 실행
        }

        if (!output.isEmpty()) {
            return output + "\n[명령어 실행 시간 초과]";
        }

        throw new CustomException(ExceptionCode.SSH_COMMAND_TIMEOUT);
    }

    private boolean isWithinTimeout(long startTime) {
        return System.currentTimeMillis() - startTime < COMMAND_TIMEOUT;
    }

    private boolean hasOutputAndCommandComplete(StringBuilder output) {
        return !output.isEmpty() && isCommandExecutionCompleted(output.toString());
    }

    private void readAndAppendAvailableOutput(StringBuilder outputBuilder) throws IOException {
        // shellOutputStream에 데이터가 있는 동안 반복하여 읽는다.
        while (shellOutputStream.available() > 0) {
            int bytesRead = shellOutputStream.read(outputBuffer);
            if (isEndOfStream(bytesRead))
                break;

            String chunk = convertBytesToString(outputBuffer, bytesRead);
            outputBuilder.append(chunk);
            publishToRedis(chunk);
        }
    }

    private void publishToRedis(String outputChunk) {
        if (redisClient == null) return;

        for (int retries = 0; retries <= MAX_REDIS_PUBLISH_RETRIES; retries++) {
            try {
                redisClient.publish(REDIS_CHANNEL_SSH, outputChunk);
                return; // 성공시 즉시 반환
            } catch (Exception e) {
                if (retries == MAX_REDIS_PUBLISH_RETRIES) {
                    log.warn("<SSHD> Redis 메시지 발행 최종 실패: {}", e.getMessage());
                    break;
                }

                try {
                    Thread.sleep(REDIS_RETRY_DELAY);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private boolean isEndOfStream(int bytesRead) {
        return bytesRead == -1;
    }

    private String convertBytesToString(byte[] buffer, int bytesRead) {
        return new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
    }

    private ByteArrayOutputStream executeAndCaptureCommandOutput(ClientChannel channel) throws IOException {
        ByteArrayOutputStream responseStream = new ByteArrayOutputStream();
        channel.setOut(responseStream);

        openExecChannel(channel);
        waitForChannelClosure(channel);

        return responseStream;
    }

    private void openExecChannel(ClientChannel channel) throws IOException {
        channel.open().verify(5, TimeUnit.SECONDS);
    }

    private void waitForChannelClosure(ClientChannel channel) {
        Set<ClientChannelEvent> channelEvents = channel.waitFor(
                EnumSet.of(ClientChannelEvent.CLOSED),
                TimeUnit.MINUTES.toMillis(5)
        );

        if (channelEvents.contains(ClientChannelEvent.TIMEOUT))
            throw new CustomException(ExceptionCode.SSH_TIME_OUT);
    }

    private String convertToUtf8String(ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8);
    }

    private boolean isCommandExecutionCompleted(String result) {
        // 기본 셸 프롬프트 패턴들
        if (result.matches(".*[$#>]\\s*$") ||
                result.contains(SHELL_PROMPT_UBUNTU) ||
                result.endsWith(SHELL_PROMPT_DOLLAR)) {
            return true;
        }

        for (Pattern pattern : SHELL_PROMPT_PATTERNS) {
            if (pattern.matcher(result).find()) {
                return true;
            }
        }

        return false;
    }

    private void applyReconnectionDelay(int attempt) throws InterruptedException {
        if (attempt > 0) {
            long delay = Math.min(
                    INITIAL_RECONNECT_DELAY * (1L << (attempt - 1)),
                    MAX_RECONNECT_DELAY
            );
            Thread.sleep(delay);
        }
    }

    private void closeResource(Object resource, String resourceName) {
        if (resource == null) return;
        try {
            if (resource instanceof ClientChannel channel) {
                channel.close(false);
            } else if (resource instanceof ClientSession session) {
                session.close(false);
            } else if (resource instanceof AutoCloseable autoCloseable) {
                autoCloseable.close();
            }
        } catch (Exception e) {
            log.debug("<SSHD> 닫기 실패 {}: {}", resourceName, e.getMessage());
        }
    }

    private void logConnectionFailure(Exception e) {
        if (e instanceof java.net.ConnectException) {
            log.error("<SSHD> 서버에 연결할 수 없습니다: 호스트={}, 포트={}", connectionConfig.getHost(), SSH_PORT);
        } else if (e instanceof java.net.SocketTimeoutException) {
            log.error("<SSHD> 연결 시간 초과: 호스트={}, 제한 시간={}초", connectionConfig.getHost(), SSH_CONNECTION_TIMEOUT);
        } else if (e.getMessage() != null && e.getMessage().contains("Auth fail")) {
            log.error("<SSHD> 인증 실패: 사용자명={}, 키 경로={}", connectionConfig.getUsername(), connectionConfig.getPrivateKeyPath());
        } else {
            log.error("<SSHD> SSH 세션 연결 실패: 호스트={}, 사용자명={}", connectionConfig.getHost(), connectionConfig.getUsername(), e);
        }
    }
}