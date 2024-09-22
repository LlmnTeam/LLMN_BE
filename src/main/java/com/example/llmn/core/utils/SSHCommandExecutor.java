package com.example.llmn.core.utils;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.PtyChannelConfiguration;
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

public class SSHCommandExecutor {
    private final SshClient client;
    private final ClientSession session;
    private final ClientChannel shellChannel;
    private final OutputStream pipedIn;
    private final InputStream pipedOut;
    private final Jedis jedis;
    private static final String REDIS_CHANNEL = "ssh-command-output"; // 고정된 Redis 채널 이름
    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private static final int SSH_PORT = 22;

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
        session.auth()
                .verify(10, TimeUnit.SECONDS); //인증이 10초 내에 성공하지 않으면 타임아웃 발생

        // 5. Redis 연결
        jedis = new Jedis(REDIS_HOST, REDIS_PORT);

        // 6. PtyChannelConfiguration 객체 생성 및 터미널 설정
        PtyChannelConfiguration ptyConfig = new PtyChannelConfiguration();
        ptyConfig.setPtyType("xterm");  // 터미널 유형 설정
        ptyConfig.setPtyColumns(80);    // 터미널 너비 설정
        ptyConfig.setPtyLines(24);      // 터미널 높이 설정
        ptyConfig.setPtyWidth(640);     // 실제 창 너비 설정
        ptyConfig.setPtyHeight(480);    // 실제 창 높이 설정

        // 7. Shell 채널을 열어 지속적인 명령어 실행을 가능하게 설정
        shellChannel = session.createShellChannel(ptyConfig, Collections.emptyMap());

        // 8. Shell 채널이 정상적으로 생성되었는지 확인
        if (shellChannel != null) {
            shellChannel.open()
                    .verify(30, TimeUnit.SECONDS); // 30초 내에 채널이 성공적으로 열렸는지 확인

            pipedIn = shellChannel.getInvertedIn(); // 표준 입력 스트림에 연결
            pipedOut = shellChannel.getInvertedOut(); // 표준 출력 스트림에 연결
        } else {
            throw new Exception("Shell 채널 초기화 실패");
        }
    }

    // 각 스레드가 동시에 동일한 SSH 세션에 접근하여 명령어를 실행하고, 동일한 pipedIn과 pipedOut 스트림에 동시 접근할 수 있는 문제 방지를 위해 syschronizrd 사용
    public synchronized String executeCommandInShell(String command) throws Exception {
        // 초기 로그인 메시지 처리 => 데이터를 읽되 저장하지 않고 버림
        if (pipedOut.available() > 0) {
            byte[] buffer = new byte[4096];

            while (pipedOut.available() > 0) {
                pipedOut.read(buffer);
            }
        }

        // 명령어를 지속적으로 입력받아 실행
        pipedIn.write((command + "\n").getBytes());
        pipedIn.flush();

        // 결과를 비동기적으로 읽음 => 결과가 출력될 때까지 대기
        StringBuilder resultBuilder = new StringBuilder();
        byte[] buffer = new byte[4096];
        boolean commandCompleted = false;

        while (!commandCompleted) {
            // 명령어 출력이 있을 때만 읽기
            while (pipedOut.available() > 0) {
                int bytesRead = pipedOut.read(buffer);
                if (bytesRead != -1) {
                    resultBuilder.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
                    jedis.publish(REDIS_CHANNEL, new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
                }
            }

            // 프롬프트를 통해 명령어 완료 확인 => 프롬프트가 나타나면 명령어가 완료된 것으로 간주
            String result = resultBuilder.toString();
            if (result.contains("ubuntu@") || result.endsWith("$ ")) {
                commandCompleted = true;
            } else {
                Thread.sleep(100); // CPU 자원 낭비 방지 차원
            }
        }

        //System.out.println("결과:" + resultBuilder);

        return resultBuilder.toString();
    }

    public String executeCommandOnce(String command) throws Exception {
        StringBuilder resultBuilder = new StringBuilder();

        try (ClientChannel channel = session.createExecChannel(command)) {
            ByteArrayOutputStream responseStream = new ByteArrayOutputStream();
            channel.setOut(responseStream);

            channel.open().verify(5, TimeUnit.SECONDS);

            // 명령어가 완료될 때까지 대기 (기본적으로 5분)
            Set<ClientChannelEvent> events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.MINUTES.toMillis(5));
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                throw new Exception("커맨드 타임아웃 발생");
            }

            resultBuilder.append(new String(responseStream.toByteArray(), StandardCharsets.UTF_8));
        }

        return resultBuilder.toString();
    }

    public void close() throws Exception {
        if (shellChannel != null) {
            shellChannel.close(false);
        }
        if (session != null) {
            session.close(false);
        }
        if (client != null) {
            client.stop();
        }
        System.out.println("SSH 세션 및 Shell 채널 종료.");
    }

    // SSH 세션이 연결되어 있는지 확인
    public boolean isConnected() {
        return session != null && session.isOpen();
    }
}
