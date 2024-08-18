package com.example.llmn.core.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;

public class LogDataParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String formatMessage(String messageJson) {
        try {
            // JSON 문자열을 JsonNode 객체로 파싱
            JsonNode rootNode = objectMapper.readTree(messageJson);

            // JSON 데이터를 그대로 다시 직렬화하여 원본 형식으로 반환
            return objectMapper.writeValueAsString(rootNode);
        } catch (IOException e) {
            e.printStackTrace();
            return messageJson;  // 파싱 실패 시 원래 메시지 반환
        }
    }
}