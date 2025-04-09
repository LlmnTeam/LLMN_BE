package com.example.llmn.domain.openai;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.user.User;
import com.example.llmn.domain.user.UserRepository;
import com.example.llmn.domain.user.model.request.RequestValidateKeyReq;
import com.example.llmn.domain.user.model.response.ValidateOpenAIKeyRes;
import com.example.llmn.security.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

import static com.example.llmn.common.utils.UriUtils.buildURI;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class OpenAiKeyService {

    private final OpenAiKeyRepository openAiKeyRepository;
    private final UserRepository userRepository;
    private final WebClient webClient;
    private final EncryptionService encryptionService;

    @Value("${validate_key.uri}")
    private String requestValidateKeyUri;

    public String getOpenAiKey(Long userId) {
        return openAiKeyRepository.findByUserId(userId)
                .map(openAiKey -> encryptionService.decrypt(openAiKey.getKeyValue()))
                .orElseThrow(() -> new CustomException(ExceptionCode.API_KEY_NOT_FOUND));
    }

    @Transactional
    public void updateOpenAIKey(String apiKey, String email) {
        Optional<OpenAiKey> existingKey = openAiKeyRepository.findByEmail(email);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        // 원본 키로 OpenAI API를 호출했을 때 처리가 잘 되는지 검증
        validateOpenAIKey(apiKey);

        // 암호화된 키 저장
        String encryptedKey = encryptionService.encrypt(apiKey);

        if (existingKey.isPresent()) {
            existingKey.get().updateKeyValue(encryptedKey, user);
        } else {
            saveEncryptedKey(encryptedKey, user);
        }
    }

    @Transactional
    public void encryptAndSaveOpenAIKey(String apiKey, User user) {
        String encryptedKey = encryptionService.encrypt(apiKey); // 암호화
        OpenAiKey openAiKey = OpenAiKey.builder()
                .keyValue(encryptedKey)
                .user(user)
                .build();
        openAiKeyRepository.save(openAiKey);
    }

    public ValidateOpenAIKeyRes validateOpenAIKey(String apiKey) {
        return webClient.post()
                .uri(buildURI(requestValidateKeyUri))
                .bodyValue(new RequestValidateKeyReq(apiKey))
                .retrieve()
                .bodyToMono(ValidateOpenAIKeyRes.class)
                .block();
    }

    private void saveEncryptedKey(String apiKey, User user) {
        OpenAiKey openAiKey = OpenAiKey.builder()
                .keyValue(apiKey)
                .tempIdentifier(null)
                .user(user)
                .build();
        openAiKeyRepository.save(openAiKey);
    }
}
