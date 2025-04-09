package com.example.llmn.common.exceptions;

import com.example.llmn.common.utils.ApiUtils;
import com.example.llmn.common.utils.ConverterUtils;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<?> customError(CustomException e) {
        log.error("CustomException 발생: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiUtils.error(e.getMessage(), HttpStatus.BAD_REQUEST));
    }

    // 유효성 검증 예외 처리
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationExceptions(MethodArgumentNotValidException ex) {
        BindingResult result = ex.getBindingResult();
        List<FieldError> fieldErrors = result.getFieldErrors();

        String errorMessage = fieldErrors.stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        return createErrorResponse(errorMessage, HttpStatus.BAD_REQUEST);
    }

    // 잘못된 데이터 형식에 대한 예외 처리 (JSON 파싱 시 발생하는 에러 등)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        String errorMessage = "요청 데이터 형식이 올바르지 않습니다.";

        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException ife) {
            if (ife.getTargetType().isEnum()) {
                String enumValues = ConverterUtils.convertEnumToString((Class<? extends Enum<?>>) ife.getTargetType());
                errorMessage = "유효하지 않은 값입니다. 허용된 값은 " + enumValues + " 입니다.";
            }
        } else if (ex.getMessage().contains("Required request body is missing")) {
            errorMessage = "요청 본문이 누락되었습니다.";
        }

        return createErrorResponse(errorMessage, HttpStatus.BAD_REQUEST);
    }

    // 누락된 요청 파라미터 예외 처리
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> handleMissingServletRequestParameterException(MissingServletRequestParameterException ex) {
        String errorMessage = "요청 파라미터 " + ex.getParameterName() + "가 누락되었습니다.";
        return createErrorResponse(errorMessage, HttpStatus.BAD_REQUEST);
    }

    // 파라미터 타입 불일치 예외 처리
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        String errorMessage = "파라미터 '" + ex.getName() + "'의 타입이 올바르지 않습니다. 예상 타입: " +
                (ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "알 수 없음");
        return createErrorResponse(errorMessage, HttpStatus.BAD_REQUEST);
    }

    // 제약 조건 위반 예외 처리
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleConstraintViolationException(ConstraintViolationException ex) {
        String errorMessage = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining(", "));
        return createErrorResponse(errorMessage, HttpStatus.BAD_REQUEST);
    }

    // 리소스를 찾을 수 없음 예외 처리
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<?> handleNoHandlerFoundException(NoHandlerFoundException ex) {
        String errorMessage = "요청한 리소스를 찾을 수 없습니다: " + ex.getRequestURL();
        return createErrorResponse(errorMessage, HttpStatus.NOT_FOUND);
    }

    // 입출력 예외 처리
    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> handleIOException(IOException ex) {
        String errorMessage = "입출력 오류가 발생했습니다: " + ex.getMessage();
        return createErrorResponse(errorMessage, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unknownServerError(Exception e) {
        log.error("처리되지 않은 예외 발생:", e);
        return createErrorResponse("서버 내부 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<?> createErrorResponse(String message, HttpStatus status) {
        return ResponseEntity.status(status).body(ApiUtils.error(message, status));
    }
}