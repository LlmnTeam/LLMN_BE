package com.example.llmn.common.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;

public class ApiUtils {

    private ApiUtils() {
    }

    public static <T> ApiResult<T> success(HttpStatus httpStatus, T result) {
        return new ApiResult<>(true, httpStatus.value(), httpStatus.getReasonPhrase(), result);
    }

    public static ApiResult<?> error(String message, HttpStatus httpStatus) {
        return new ApiResult<>(false, httpStatus.value(), message, null);
    }

    @Getter
    @AllArgsConstructor
    public static class ApiResult<T> {
        private final boolean success;
        private final int code;
        private final String message;
        private final T result;
    }
}
