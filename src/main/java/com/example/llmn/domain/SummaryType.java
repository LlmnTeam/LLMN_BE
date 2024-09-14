package com.example.llmn.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum SummaryType {

    GENERAL("일반요약"),
    ANOMALY("비정상"),
    PERFORMANCE("성능"),
    DAILY("일일"),
    TEND("트렌드"),
    RECOMMENDATION("추천");

    private String value;
}
