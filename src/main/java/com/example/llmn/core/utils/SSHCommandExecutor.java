package com.example.llmn.core.utils;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.PtyChannelConfiguration;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SSHCommandExecutor {
    private SshClient client;
    private ClientSession session;
    private ClientChannel shellChannel;
    private OutputStream pipedIn;
    private InputStream pipedOut;

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
            // Shell 채널을 열고 나서 pipedIn 및 pipedOut 설정
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

        // 명령어를 지속적으로 입력받아 실행
        pipedIn.write((command + "\n").getBytes());
        pipedIn.flush();

        // 결과를 비동기적으로 읽음 (일정 시간 동안 대기하며 결과 수집)
        StringBuilder resultBuilder = new StringBuilder();
        byte[] buffer = new byte[4096];
        long timeout = System.currentTimeMillis() + 5000; // 5초 대기 (필요에 따라 조정 가능)

        while (System.currentTimeMillis() < timeout && pipedOut.available() > 0) {
            int bytesRead = pipedOut.read(buffer);
            if (bytesRead != -1) {
                resultBuilder.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
            }
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
