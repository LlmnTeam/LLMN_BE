package com.example.llmn.core.utils;

import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.loader.KeyPairResourceParser;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Collection;

public class SshUtils {

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
}
