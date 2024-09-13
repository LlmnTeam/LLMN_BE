package com.example.llmn.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum SummaryType {

    GENERAL("일반요약"),
    ANOMALY("비정상"),
    PERFORMANCE("성능");

    private String value;
}
