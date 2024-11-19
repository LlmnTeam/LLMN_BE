package com.example.llmn.core.utils;

import java.util.Arrays;
import java.util.stream.Collectors;

public class ConverterUtils {

    public static String convertEnumToString(Class<? extends Enum<?>> e) {
        return Arrays.stream(e.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }

    public static long convertStringToLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}