package com.example.llmn.core.utils;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
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
    private SshClient client;
    private ClientSession session;
    private ClientChannel shellChannel;
    private OutputStream pipedIn;
    private InputStream pipedOut;
    private Jedis jedis;
    private static final String REDIS_CHANNEL = "ssh-command-output"; // 고정된 Redis 채널 이름

    public SSHCommandExecutor(String host, String username, String privateKeyPath) throws Exception {
        client = SshClient.setUpDefaultClient();
        client.start();

        if (privateKeyPath.startsWith("file://")) {
            privateKeyPath = Paths.get(URI.create(privateKeyPath)).toString();
        }

        ConnectFuture connectFuture = client.connect(username, host, 22);
        session = connectFuture.verify(30, TimeUnit.SECONDS).getSession();

        KeyPair keyPair = SshUtils.loadKeyPair(privateKeyPath);
        session.addPublicKeyIdentity(keyPair);
        session.auth().verify(30, TimeUnit.SECONDS);

        System.out.println("SSH 세션 연결 및 인증 완료");

        // Redis 연결
        jedis = new Jedis("localhost", 6379);

        // PtyChannelConfiguration 객체 생성 및 설정
        PtyChannelConfiguration ptyConfig = new PtyChannelConfiguration();
        ptyConfig.setPtyType("xterm");  // 터미널 유형 설정
        ptyConfig.setPtyColumns(80);    // 터미널 너비 설정
        ptyConfig.setPtyLines(24);      // 터미널 높이 설정
        ptyConfig.setPtyWidth(640);     // 실제 창 너비 설정
        ptyConfig.setPtyHeight(480);    // 실제 창 높이 설정

        // Shell 채널을 열어 지속적인 명령어 실행을 가능하게 함
        shellChannel = session.createShellChannel(ptyConfig, Collections.emptyMap());

        if (shellChannel != null) {
            shellChannel.open().verify(30, TimeUnit.SECONDS);
            pipedIn = shellChannel.getInvertedIn();
            pipedOut = shellChannel.getInvertedOut();

            System.out.println("SSH 세션 연결 및 Shell 채널 열림.");
        } else {
            throw new Exception("Shell 채널 초기화 실패");
        }
    }

    public synchronized String executeCommand(String command) throws Exception {
        if (pipedIn == null || pipedOut == null) {
            throw new IllegalStateException("Shell 채널이 초기화되지 않았습니다.");
        }

        StringBuilder resultBuilder = new StringBuilder();

        // 명령어를 지속적으로 입력받아 실행
        pipedIn.write((command + "\n").getBytes());
        pipedIn.flush();

        // Redis publisher 설정 (명령어 실행 결과 실시간 전송)
        PipedOutputStream pipedOutStd = new PipedOutputStream();
        PipedOutputStream pipedErrStd = new PipedOutputStream();
        PipedInputStream pipedInOut = new PipedInputStream(pipedOutStd);
        PipedInputStream pipedInErr = new PipedInputStream(pipedErrStd);

        // 표준 출력 스트림 처리 (stdout)
        Thread stdoutThread = new Thread(() -> {
            try {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = pipedInOut.read(buffer)) != -1) {
                    String output = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    jedis.publish(REDIS_CHANNEL, output); // 고정된 Redis 채널로 실시간 명령어 출력 전송
                    resultBuilder.append(output); // 결과에 추가
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        // 표준 에러 스트림 처리 (stderr)
        Thread stderrThread = new Thread(() -> {
            try {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = pipedInErr.read(buffer)) != -1) {
                    String errorOutput = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    jedis.publish(REDIS_CHANNEL, "ERROR: " + errorOutput); // 에러 메시지 전송
                    resultBuilder.append("ERROR: ").append(errorOutput); // 에러 메시지 결과에 추가
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        stdoutThread.start();
        stderrThread.start();

        // 표준 출력 및 표준 에러 연결
        shellChannel.setOut(pipedOutStd);   // 표준 출력
        shellChannel.setErr(pipedErrStd);   // 표준 에러 출력

        // 채널이 정상적으로 열렸는지 확인
        shellChannel.open().verify(30, TimeUnit.SECONDS);

        // 채널이 닫힐 때까지 대기
        Set<ClientChannelEvent> events = shellChannel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.MINUTES.toMillis(5));
        if (events.contains(ClientChannelEvent.TIMEOUT)) {
            throw new Exception("커맨드 타임아웃 발생");
        }

        // 종료 상태 확인
        Integer exitStatus = shellChannel.getExitStatus();
        if (exitStatus != null) {
            resultBuilder.append("\n명령어 종료 상태: ").append(exitStatus);
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
}
