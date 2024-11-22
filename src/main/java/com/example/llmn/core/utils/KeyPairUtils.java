package com.example.llmn.core.utils;

import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.loader.KeyPairResourceParser;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.*;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Collection;

public class KeyPairUtils {

    private KeyPairUtils() {}

    private static final FilePasswordProvider DEFAULT_PASSWORD_PROVIDER = FilePasswordProvider.EMPTY;

    public static KeyPair loadKeyPair(String pemKeyPath) throws IOException, GeneralSecurityException {
        File pemKey = validatePemKey(pemKeyPath);

        try (InputStream inputStream = new FileInputStream(pemKey)) {
            return parseKeyPair(pemKey, inputStream);
        }
    }

    private static File validatePemKey(String pemKeyPath) throws IOException {
        File pemKey = new File(pemKeyPath);
        if (!pemKey.exists()) {
            throw new IOException("Pem 키 파일이 존재하지 않습니다: " + pemKeyPath);
        }
        if (!pemKey.canRead()) {
            throw new IOException("Pem 키 파일을 읽을 수 없습니다: " + pemKeyPath);
        }

        return pemKey;
    }

    private static KeyPair parseKeyPair(File pemKey, InputStream inputStream) throws GeneralSecurityException, IOException {
        KeyPairResourceParser parser = SecurityUtils.getKeyPairResourceParser();
        if (parser == null) {
            throw new GeneralSecurityException("등록된 KeyPair 파서를 찾을 수 없습니다.");
        }

        Collection<KeyPair> keyPairs = parser.loadKeyPairs(null, createResourceKey(pemKey), DEFAULT_PASSWORD_PROVIDER, inputStream);
        validateKeyPairs(keyPairs);

        return getFirstKeyPair(keyPairs);
    }

    private static NamedResource createResourceKey(File pemKey) {
        return pemKey::getName;
    }

    private static void validateKeyPairs(Collection<KeyPair> keyPairs) throws IOException {
        if (GenericUtils.isEmpty(keyPairs)) {
            throw new IOException("SSH 접속 시 필요한 KeyPair 로드 실패");
        }
    }

    private static KeyPair getFirstKeyPair(Collection<KeyPair> keyPairs) {
        return keyPairs.iterator().next();
    }
}