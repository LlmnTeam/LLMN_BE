package com.example.llmn.core.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

@Slf4j
public class JsonUtils {

    private JsonUtils() {}

    private static final String BLANK_STRING = "";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String normalizeJson(String jsonValue) {
        try {
            JsonNode rootNode = objectMapper.readTree(jsonValue);
            return objectMapper.writeValueAsString(rootNode);
        } catch (IOException e) {
            return jsonValue;
        }
    }

    public static String convertMapToJson(Map<String, Map<String, String>> map){
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return BLANK_STRING;
        }
    }

    public static Map<String, Map<String, String>> convertJsonToMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Map<String, String>>>() {});
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }
}