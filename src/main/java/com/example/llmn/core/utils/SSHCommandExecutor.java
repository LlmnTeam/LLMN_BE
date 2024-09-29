package com.example.llmn.core.utils;

import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.PtyChannelConfiguration;
import org.springframework.web.socket.WebSocketSession;
import redis.clients.jedis.Jedis;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SSHCommandExecutor {
    private final SshClient client;
    private final ClientSession session;
    private final ClientChannel shellChannel;
    private final OutputStream pipedIn;
    private final InputStream pipedOut;
    private final Jedis jedis;

    private static final int REDIS_PORT = 6379;
    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_TIMEOUT = 60000; // 1분
    private static final String REDIS_CHANNEL = "ssh-command-output"; // 고정된 Redis 채널 이름
    private static final String PROMPT_UBUNTU = "ubuntu@";
    private static final String PROMPT_DOLLAR = "$ ";
    private static final int SSH_PORT = 22;
    private static final String FAIL_COMMAND = "명령어 실행에 실패하였습니다.";

    public SSHCommandExecutor(String host, String username, String privateKeyPath) throws Exception {
        // 1. SSH 클라이언트를 기본 설정으로 초기화 => SSH 클라이언트를 시작하여 연결을 수락할 준비를 함
        client = SshClient.setUpDefaultClient();
        client.start();

        if (privateKeyPath.startsWith("file://")) {
            privateKeyPath = Paths.get(URI.create(privateKeyPath)).toString();
        }

        // 2. SSH 서버에 연결
        ConnectFuture connectFuture = client.connect(username, host, SSH_PORT);
        session = connectFuture
                .verify(10, TimeUnit.SECONDS) // 10초 안에 연결을 확인하고 세션을 얻음
                .getSession();

        // 3. 인증을 위해 개인 키를 로드하고 SSH 세션에 공개 키 인증을 추가
        KeyPair keyPair = KeyPairUtils.loadKeyPair(privateKeyPath);
        session.addPublicKeyIdentity(keyPair);

        // 4. 세션 인증 수행
        session.auth().verify(10, TimeUnit.SECONDS);

        // 5. Jedis 객체 초기화
        jedis = new Jedis(REDIS_HOST, REDIS_PORT, REDIS_TIMEOUT);

        // 6. Shell 채널 설정을 위한 PtyChannelConfiguration 객체
        PtyChannelConfiguration ptyConfig = new PtyChannelConfiguration();
        ptyConfig.setPtyType("xterm");  // 터미널 유형 설정
        ptyConfig.setPtyColumns(160);    // 터미널 너비 설정
        ptyConfig.setPtyLines(24);      // 터미널 높이 설정
        ptyConfig.setPtyWidth(640);     // 실제 창 너비 설정
        ptyConfig.setPtyHeight(480);    // 실제 창 높이 설정

        // 7. ShellChannel 객체 생성
        shellChannel = session.createShellChannel(ptyConfig, Collections.emptyMap());

        // 8. Shell 체널 오픈
        if (shellChannel != null) {
            shellChannel.open().verify(10, TimeUnit.SECONDS);

            pipedIn = shellChannel.getInvertedIn(); // 표준 입력 스트림에 연결
            pipedOut = shellChannel.getInvertedOut(); // 표준 출력 스트림에 연결
        } else {
            throw new CustomException(ExceptionCode.SHELL_CONNECT_FAIL);
        }

        // 9. 리눅스 인사 메시지 삭제
        flushInitialMessage();
    }

    // 각 스레드가 동시에 동일한 SSH 세션에 접근하여 명령어를 실행하고, 동일한 pipedIn과 pipedOut 스트림에 동시 접근할 수 있는 문제 방지를 위해 syschronizrd 사용
    public synchronized String executeCommandInShell(String command) {
        try {
            clearOutputStream();

            // 명령어를 지속적으로 입력받아 실행
            pipedIn.write((command + "\n").getBytes());
            pipedIn.flush();

            // 결과를 비동기적으로 읽음 => 응답이 완료될 때까지
            StringBuilder resultBuilder = new StringBuilder();
            byte[] buffer = new byte[4096];
            boolean commandCompleted = false;

            while (!commandCompleted) {
                readAvailableOutput(resultBuilder, buffer);

                // 결과값을 통해 완료 여부 체크 => 프롬프트가 나타나면 완료된 것으로 간주
                commandCompleted = checkIfCommandCompleted(resultBuilder.toString());
                if (!commandCompleted) {
                    Thread.sleep(100); // CPU 자원 낭비 방지
                }
            }
            return resultBuilder.toString();
        } catch (IOException e){
            log.info("<SSHD> ShellChannel에서 '" + command + "' 명령어 실행 실패 : " + e);
            return FAIL_COMMAND;
        } catch (InterruptedException e) {
            log.info("<SSHD> 명령어 실행 중 쓰레드에 문제 발생 : " + e);
            return FAIL_COMMAND;
        }
    }

    public String executeCommandOnce(String command)  {
        StringBuilder resultBuilder = new StringBuilder();

        try (ClientChannel channel = session.createExecChannel(command)) {
            ByteArrayOutputStream responseStream = new ByteArrayOutputStream();
            channel.setOut(responseStream);

            channel.open().verify(5, TimeUnit.SECONDS);

            // 명령어가 완료될 때까지 대기 (기본적으로 5분)
            Set<ClientChannelEvent> events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.MINUTES.toMillis(5));
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                throw new CustomException(ExceptionCode.SSH_TIME_OUT);
            }

            resultBuilder.append(new String(responseStream.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e){
            log.info("<SSHD> ClientChannel에서 '" + command + "' 명령어 실행 실패 : " + e);
            throw new CustomException(ExceptionCode.SSH_COMMAND_FAIL);
        }

        return resultBuilder.toString();
    }

    public void close()  {
        if (shellChannel != null) {
            shellChannel.close(false);
        }
        if (session != null) {
            session.close(false);
        }
        if (client != null) {
            client.stop();
        }

        log.info("SSH 세션 및 Shell 채널 종료.");
    }

    // SSH 세션이 연결되어 있는지 확인
    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    private void clearOutputStream() throws IOException {
        if (pipedOut.available() > 0) {
            byte[] buffer = new byte[4096];

            // 데이터를 읽어 버림
            while (pipedOut.available() > 0) {
                pipedOut.read(buffer);
            }
        }
    }

    private void readAvailableOutput(StringBuilder resultBuilder, byte[] buffer) throws IOException {
        while (pipedOut.available() > 0) {
            int bytesRead = pipedOut.read(buffer);

            if (bytesRead != -1) {
                String output = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                resultBuilder.append(output);

                // Redis에 읽고 있는 데이터 전송
                jedis.publish(REDIS_CHANNEL, output);
            }
        }
    }

    private void flushInitialMessage() {
        StringBuilder resultBuilder = new StringBuilder();
        byte[] buffer = new byte[4096];

        try {
            while (checkIfCommandCompleted(resultBuilder.toString())) {
                while (pipedOut.available() > 0) {
                    int bytesRead = pipedOut.read(buffer);

                    if (bytesRead != -1) {
                        resultBuilder.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException e){
            log.info("리눅스 초기 메시지 flush 작업 실패!");
        }
    }

    private boolean checkIfCommandCompleted(String result) {
        return result.contains(PROMPT_UBUNTU) || result.endsWith(PROMPT_DOLLAR);
    }
}