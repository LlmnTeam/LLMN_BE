package com.example.llmn.core.utils;

import java.util.Map;
import java.util.Optional;

public class MapUtils {

    private MapUtils() {}

    public static String extractStringFromMap(Map<String, Object> map, String key, String defaultValue) {
        return Optional.ofNullable((String) map.get(key)).orElse(defaultValue);
    }

    public static boolean extractBooleanFromMap(Map<String, Object> map, String key, boolean defaultValue) {
        return Optional.ofNullable((Boolean) map.get(key)).orElse(defaultValue);
    }
}
