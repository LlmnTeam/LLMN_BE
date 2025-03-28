package com.example.llmn.domain.openai;

import com.example.llmn.common.utils.ApiUtils;
import com.example.llmn.domain.user.model.request.UpdateApiKeyReq;
import com.example.llmn.domain.user.model.request.ValidateOpenAIKeyReq;
import com.example.llmn.domain.user.model.response.ValidateOpenAIKeyRes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class OpenAiController {

    private final OpenAiKeyService openAiKeyService;

    @PostMapping("/accounts/validate/key")
    public ResponseEntity<?> validateOpenAIKey(@RequestBody @Valid ValidateOpenAIKeyReq requestDTO){
        ValidateOpenAIKeyRes responseDTO = openAiKeyService.validateOpenAIKey(requestDTO.apiKey());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PatchMapping("/accounts/apiKey")
    public ResponseEntity<?> updateApiKey(@RequestBody @Valid UpdateApiKeyReq requestDTO){
        openAiKeyService.updateOpenAIKey(requestDTO.apiKey(), requestDTO.email());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }
}
