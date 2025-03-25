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

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
public class MinaSshdService {
    private final SshClient client;
    private final ClientSession session;
    private final ClientChannel shellChannel;
    private final OutputStream pipedIn;
    private final InputStream pipedOut;
    private final Jedis jedis;

    private static final int AUTH_TIMEOUT = 10;
    private static final int CONNECTION_TIMEOUT = 5;
    private static final int SHELL_CHANNEL_TIMEOUT = 10;
    private static final int REDIS_PORT = 6379;
    private static final String REDIS_HOST = "redis";
    private static final int REDIS_TIMEOUT = 60000; // 1분
    private static final String REDIS_CHANNEL = "ssh-command-output"; // 고정된 Redis 채널 이름
    private static final String PROMPT_UBUNTU = "ubuntu@";
    private static final String PROMPT_DOLLAR = "$ ";
    private static final int SSH_PORT = 22;
    private static final String FAIL_COMMAND = "명령어 실행에 실패하였습니다.";
    private static final String PTY_TYPE = "xterm";
    private static final int PTY_COLUMNS = 160;
    private static final int PTY_LINES = 24;
    private static final int PTY_WIDTH = 640;
    private static final int PTY_HEIGHT = 480;
    private static final int BUFFER_SIZE = 4096;
    private static final int SLEEP_DURATION_MS = 100;
    private static final String BLANK_STRING = "";
    private static final int ASCII_SIGINT_SIGNAL = 3; // ASCII 0x03은 SIGINT 신호

    public MinaSshdService(String host, String username, String privateKeyPath) {
        this.client = initializeSSHClient();
        this.session = connectToSession(host, username, privateKeyPath);
        this.shellChannel = createShellChannel();
        this.jedis = initializeJedis();
        this.pipedIn = shellChannel.getInvertedIn();
        this.pipedOut = shellChannel.getInvertedOut();
    }

    public synchronized String executeCommandInShell(String command) {
        try {
            clearOutputStream();
            writeCommand(command);
            return readCommandOutput();
        } catch (IOException e){
            log.error("<SSHD> ShellChannel에서 '{}' 명령어 실행 실패: {}", command, e.getMessage());
            return FAIL_COMMAND;
        } catch (InterruptedException e) {
            log.error("<SSHD> 명령어 실행 중 쓰레드에 문제 발생 : {}", String.valueOf(e));
            Thread.currentThread().interrupt();
            return FAIL_COMMAND;
        }
    }

    public String executeCommandOnce(String command)  {
        if (!session.isOpen()) {
            return BLANK_STRING;
        }

        try (ClientChannel channel = session.createExecChannel(command)) {
            ByteArrayOutputStream responseStream = executeCommandInExecChannel(channel);
            return decodeByteArrayStream(responseStream);
        } catch (IOException e){
            log.error("<SSHD> ClientChannel에서 '{}' 명령어 실행 실패: {}", command, e.getMessage());
            throw new CustomException(ExceptionCode.SSH_COMMAND_FAIL);
        }
    }

    public void flushInitialMessage() {
        try {
            readCommandOutput();
        } catch (IOException e) {
            log.error("초기 명령어 flush 실패", e);
        } catch (InterruptedException e) {
            log.error("초기 명령어 flush 중 인터럽트 발생", e);
            Thread.currentThread().interrupt();
        }
    }

    public void sendSigint() {
        try {
            pipedIn.write(ASCII_SIGINT_SIGNAL);
            pipedIn.flush();
        } catch (IOException e) {
            log.error("SIGINT 신호 전송 실패: {}", e.getMessage());
        }
    }

    public void close() {
        if (shellChannel != null) {
            shellChannel.close(false);
        }
        if (session != null) {
            session.close(false);
        }
        if (client != null) {
            client.stop();
        }
        if (jedis != null) {
            jedis.close();
        }
    }

    public boolean isConnected() {
        return client != null && client.isOpen() && session != null && session.isOpen();
    }

    private SshClient initializeSSHClient() {
        SshClient sshClient = SshClient.setUpDefaultClient();
        sshClient.start();
        return sshClient;
    }

    private ClientSession connectToSession(String host, String username, String privateKeyPath) {
        try {
            if (privateKeyPath.startsWith("file://")) {
                privateKeyPath = Paths.get(URI.create(privateKeyPath)).toString();
            }

            ConnectFuture connectFuture = client.connect(username, host, SSH_PORT);
            ClientSession clientSession = connectFuture.verify(CONNECTION_TIMEOUT, TimeUnit.SECONDS).getSession();

            KeyPair keyPair = KeyPairUtils.loadKeyPair(privateKeyPath);
            clientSession.addPublicKeyIdentity(keyPair);
            clientSession.auth().verify(AUTH_TIMEOUT, TimeUnit.SECONDS);

            return clientSession;
        } catch (Exception e) {
            log.error("SSH 세션 연결 실패: host={}, username={}", host, username, e);
            throw new CustomException(ExceptionCode.SSH_CONNECT_FAIL);
        }
    }

    private ClientChannel createShellChannel() {
        try {
            PtyChannelConfiguration ptyConfig = createPtyChannelConfiguration();

            ClientChannel channel = session.createShellChannel(ptyConfig, Collections.emptyMap());
            verifyChannelOpened(channel);

            return channel;
        } catch (IOException e) {
            log.error("Shell 채널 생성 중 오류 발생", e);
            throw new CustomException(ExceptionCode.SHELL_CONNECT_FAIL);
        }
    }

    private PtyChannelConfiguration createPtyChannelConfiguration() {
        PtyChannelConfiguration ptyConfig = new PtyChannelConfiguration();
        ptyConfig.setPtyType(PTY_TYPE);
        ptyConfig.setPtyColumns(PTY_COLUMNS);
        ptyConfig.setPtyLines(PTY_LINES);
        ptyConfig.setPtyWidth(PTY_WIDTH);
        ptyConfig.setPtyHeight(PTY_HEIGHT);

        Map<PtyMode, Integer> terminalModes = new EnumMap<>(PtyMode.class);
        terminalModes.put(PtyMode.ECHO, 0);
        ptyConfig.setPtyModes(terminalModes);

        return ptyConfig;
    }

    private void verifyChannelOpened(ClientChannel channel) throws IOException {
        channel.open().verify(SHELL_CHANNEL_TIMEOUT, TimeUnit.SECONDS);
    }

    private Jedis initializeJedis() {
        return new Jedis(REDIS_HOST, REDIS_PORT, REDIS_TIMEOUT);
    }

    private void clearOutputStream() throws IOException {
        if (pipedOut.available() > 0) {
            byte[] buffer = new byte[4096];

            while (pipedOut.available() > 0) {
                pipedOut.read(buffer);
            }
        }
    }

    private void writeCommand(String command) throws IOException {
        pipedIn.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        pipedIn.flush();
    }

    private String readCommandOutput() throws IOException, InterruptedException {
        StringBuilder resultBuilder = new StringBuilder();
        byte[] buffer = new byte[BUFFER_SIZE];

        while (true) {
            readAvailableOutput(resultBuilder, buffer);
            if (checkIfCommandCompleted(resultBuilder.toString())) {
                break;
            }

            Thread.sleep(SLEEP_DURATION_MS); // CPU 자원 낭비 방지
        }

        return resultBuilder.toString();
    }

    private void readAvailableOutput(StringBuilder resultBuilder, byte[] buffer) throws IOException {
        while (pipedOut.available() > 0) {
            int bytesRead = pipedOut.read(buffer);

            if (isEndOfStream(bytesRead)) {
                break;
            }

            String output = decodeToUtf8(buffer, bytesRead);
            resultBuilder.append(output);
            jedis.publish(REDIS_CHANNEL, output);
        }
    }

    private boolean isEndOfStream(int bytesRead) {
        return bytesRead == -1;
    }

    private String decodeToUtf8(byte[] buffer, int bytesRead) {
        return new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
    }

    private ByteArrayOutputStream executeCommandInExecChannel(ClientChannel channel) throws IOException {
        ByteArrayOutputStream responseStream = new ByteArrayOutputStream();
        channel.setOut(responseStream);

        openChannel(channel);
        waitForChannelClosure(channel);

        return responseStream;
    }

    private void openChannel(ClientChannel channel) throws IOException {
        channel.open().verify(5, TimeUnit.SECONDS);
    }

    private void waitForChannelClosure(ClientChannel channel) {
        Set<ClientChannelEvent> events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.MINUTES.toMillis(5));
        if (events.contains(ClientChannelEvent.TIMEOUT)) {
            throw new CustomException(ExceptionCode.SSH_TIME_OUT);
        }
    }

    private String decodeByteArrayStream(ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8);
    }

    private boolean checkIfCommandCompleted(String result) {
        return result.contains(PROMPT_UBUNTU) || result.endsWith(PROMPT_DOLLAR);
    }
}