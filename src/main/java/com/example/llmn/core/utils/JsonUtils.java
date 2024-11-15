package com.example.llmn.core.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

public class JsonUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String normalizeJson(String jsonValue) {
        try {
            JsonNode rootNode = objectMapper.readTree(jsonValue);
            return objectMapper.writeValueAsString(rootNode);
        } catch (IOException e) {
            return jsonValue;  // 파싱 실패 시 원래 메시지 반환
        }
    }
}