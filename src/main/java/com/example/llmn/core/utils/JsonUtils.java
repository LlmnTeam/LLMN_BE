package com.example.llmn.core.utils;

import com.example.llmn.controller.DTO.MetricResponse;
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

    private static final String EMPTY_JSON = "{}";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String normalizeJson(String jsonValue) {
        try {
            JsonNode rootNode = objectMapper.readTree(jsonValue);
            return objectMapper.writeValueAsString(rootNode);
        } catch (IOException e) {
            log.warn("JSON 문자열을 정상화할 수 없습니다: {}", jsonValue, e);
            return jsonValue;
        }
    }

    public static String convertMapToJson(Map<String, Map<String, String>> map){
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("맵을 JSON 문자열로 변환하는 중 오류 발생", e);
            return EMPTY_JSON;
        }
    }

    public static Map<String, Map<String, String>> convertJsonToMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Map<String, String>>>() {});
        } catch (JsonProcessingException e) {
            log.error("JSON 문자열을 맵으로 변환하는 중 오류 발생: {}", json, e);
            return Collections.emptyMap();
        }
    }

    public static MetricResponse.FindCurrentMetricDTO convertJsonToMetricDTO(String json) {
        try {
            return objectMapper.readValue(json, MetricResponse.FindCurrentMetricDTO.class);
        } catch (JsonProcessingException e) {
            log.error("JSON을 MetricDTO로 변환하는 중 오류 발생: {}", json, e);
            return null;
        }
    }

    public static String convertMetricDtoToJson(MetricResponse.FindCurrentMetricDTO metricDTO){
        try {
            return objectMapper.writeValueAsString(metricDTO);
        } catch (JsonProcessingException e) {
            log.error("MetricDTO를 JSON 문자열로 변환하는 중 오류 발생: {}", metricDTO, e);
            return EMPTY_JSON;
        }
    }

    public static boolean isNotEmpty(String json) {
        return !EMPTY_JSON.equals(json);
    }
}