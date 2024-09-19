package com.example.llmn.core.utils;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.loader.KeyPairResourceParser;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class SshUtils {

    private static final int SSH_PORT_NUM = 22;

    public static KeyPair loadKeyPair(String privateKeyPath) throws IOException, GeneralSecurityException {
        // privateKeyPath에 해당하는 파일 객체 생성
        File privateKeyFile = new File(privateKeyPath);

        if (!privateKeyFile.exists()) {
            throw new IOException("Pem 키 파일이 존재하지 않음: " + privateKeyPath);
        }

        // 파일 입력 스트림을 열어 키 파일을 읽어들임
        try (InputStream inputStream = new FileInputStream(privateKeyFile)) {
            // NamedResource는 SSHD 라이브러리에서 파일의 이름을 나타내는 인터페이스
            NamedResource resourceKey = new NamedResource() {
                @Override
                public String getName() {
                    return privateKeyFile.getName();
                }
            };

            FilePasswordProvider provider = FilePasswordProvider.EMPTY; // 패스프레이즈 없는 경우
            SessionContext session = null; // 특정 세션이 필요하지 않아서 null로 설정

            // SecurityUtils를 통해 등록된 KeyPairResourceParser를 가져옴
            KeyPairResourceParser parser = SecurityUtils.getKeyPairResourceParser();
            if (parser == null) {
                throw new GeneralSecurityException("등록된 key-pair 파서가 존재하지 않음.");
            }

            // 키 파일을 읽어 KeyPair 객체들을 로드
            Collection<KeyPair> keyPairs = parser.loadKeyPairs(session, resourceKey, provider, inputStream);
            if (GenericUtils.isEmpty(keyPairs)) {
                throw new IOException("keyPair 로드 실패: " + privateKeyPath);
            }

            return keyPairs.iterator().next(); // 첫 번째 KeyPair 반환
        }
    }

    public static String executeCommand(String host, String username, String privateKeyPath, String command) throws Exception {
        SshClient client = null;
        ClientSession session = null;
        StringBuilder resultBuilder = new StringBuilder();

        try {
            // SSH 클라이언트 생성
            client = SshClient.setUpDefaultClient();
            client.start();

            // 파일 경로 설정 (file:// 경로 지원)
            if (privateKeyPath.startsWith("file://")) {
                privateKeyPath = Paths.get(URI.create(privateKeyPath)).toString();
            }

            // 클라이언트 세션 생성
            ConnectFuture connectFuture = client.connect(username, host, SSH_PORT_NUM);
            session = connectFuture.verify(10, TimeUnit.SECONDS).getSession();

            // 키 파일로 인증 설정
            KeyPair keyPair = SshUtils.loadKeyPair(privateKeyPath);
            session.addPublicKeyIdentity(keyPair);

            // 연결
            session.auth().verify(10, TimeUnit.SECONDS);

            // 명령어 실행
            String commandOutput = executeRemoteCommand(session, command);
            resultBuilder.append(commandOutput);

        } catch (Exception e) {
            e.printStackTrace();
            return "에러: " + e.getMessage();
        } finally {
            if (session != null) {
                session.close(false);
            }
            if (client != null) {
                client.stop();
            }
        }

        return resultBuilder.toString();
    }

    private static String executeRemoteCommand(ClientSession session, String command) throws Exception {
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
}
