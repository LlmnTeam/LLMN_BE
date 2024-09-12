package com.example.llmn.core.errors;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
@Getter
public enum ExceptionCode {
    // 사용자 관련 에러
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 사용자를 찾을 수 없습니다."),
    USER_EMAIL_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 이메일을 찾을 수 없습니다."),
    USER_EMAIL_EXIST(HttpStatus.BAD_REQUEST, "이미 존재하는 이메일입니다."),
    USER_NICKNAME_EXIST(HttpStatus.BAD_REQUEST, "이미 존재하는 닉네임입니다."),
    USER_ACCOUNT_WRONG(HttpStatus.BAD_REQUEST, "이메일 또는 비밀번호를 다시 확인해 주세요"),
    USER_PASSWORD_WRONG(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다."),
    USER_PASSWORD_MATCH_WRONG(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다."), // 두 비밀번호 일치 여부 확인
    USER_FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    USER_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 되지 않았습니다."),
    USER_ALREADY_EXIT(HttpStatus.NOT_FOUND, "이미 탈퇴한 계정입니다."),

    // 로그 파일 관련 에러
    LOG_DIRECTORY_NOT_FOUND(HttpStatus.NOT_FOUND, "로그 디렉토리가 존재하지 않거나 디렉토리가 아닙니다."),
    LOG_FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 로그 파일이 존재하지 않습니다."),
    LOG_FILE_READ_FAIL(HttpStatus.BAD_REQUEST, "로그 파일을 읽는 중 오류 발생했습니다."),
    LOG_FILE_LIST_READ_FAIL(HttpStatus.BAD_REQUEST, "로그 파일 목록을 가져오는 중 오류 발생했습니다."),

    // 프로젝트 관련 에러
    PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 프로젝트를 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;
}
