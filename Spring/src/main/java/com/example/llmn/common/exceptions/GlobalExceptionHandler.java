package com.example.llmn.common.exceptions;

import com.example.llmn.common.utils.ApiUtils;
import com.example.llmn.common.utils.ConverterUtils;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.io.IOException;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<?> customError(CustomException e) {
        return ResponseEntity.badRequest().body(ApiUtils.error(e.getMessage(), HttpStatus.BAD_REQUEST));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unknownServerError(Exception e){
        e.printStackTrace(); // 콘솔에 찍기
        return ResponseEntity.internalServerError().body(ApiUtils.error(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationExceptions(MethodArgumentNotValidException ex) {
        BindingResult result = ex.getBindingResult();
        String errorMessage = result.getFieldError().getDefaultMessage();
        return ResponseEntity.badRequest().body(ApiUtils.error(errorMessage, HttpStatus.BAD_REQUEST));
    }

    // 잘못된 데이터 형식에 대한 예외처리 (JSON 파싱 시 발생하는 에러 등)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        String errorMessage = "요청 데이터 형식이 올바르지 않습니다.";

        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException ife) {
            if (ife.getTargetType().isEnum()) {
                String enumValues = ConverterUtils.convertEnumToString((Class<? extends Enum<?>>) ife.getTargetType());
                errorMessage = "유효하지 않은 값입니다. 허용된 값은 " + enumValues + " 입니다.";
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiUtils.error(errorMessage, HttpStatus.BAD_REQUEST));
            }
        } else if (ex.getMessage().contains("Required request body is missing"))
            errorMessage = "요청 본문이 누락되었습니다.";

        return ResponseEntity.badRequest().body(ApiUtils.error(errorMessage, HttpStatus.BAD_REQUEST));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> handleMissingServletRequestParameterException(MissingServletRequestParameterException ex) {
        String errorMessage = "요청 파라미터 " + ex.getParameterName() + "가 누락되었습니다.";
        return ResponseEntity.badRequest().body(ApiUtils.error(errorMessage, HttpStatus.BAD_REQUEST));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> handleIOException(IOException ex) {
        String errorMessage = "입출력 오류가 발생했습니다: " + ex.getMessage();
        return ResponseEntity.internalServerError().body(ApiUtils.error(errorMessage, HttpStatus.INTERNAL_SERVER_ERROR));
    }
}