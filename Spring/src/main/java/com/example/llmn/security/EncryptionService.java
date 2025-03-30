package com.example.llmn.security;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Base64;

@Service
public class EncryptionService {

    @Value("${encryption.secret-key}")
    private String secretKey;

    @Value("${encryption.algorithm}")
    private String algorithm;

    public String encrypt(String data) {
        try {
            Key key = generateKey();
            Cipher cipher = Cipher.getInstance(algorithm);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encryptedBytes = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            throw new CustomException(ExceptionCode.ENCRYPTION_FAILED);
        }
    }

    public String decrypt(String encryptedData) {
        try {
            Key key = generateKey();
            Cipher cipher = Cipher.getInstance(algorithm);
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedData));
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CustomException(ExceptionCode.DECRYPTION_FAILED);
        }
    }

    private Key generateKey() {
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        // 키는 16, 24, 또는 32 바이트여야 함
        return new SecretKeySpec(keyBytes, algorithm);
    }
}
