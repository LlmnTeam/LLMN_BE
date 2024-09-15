package com.example.llmn.service;

import com.example.llmn.controller.DTO.InsightResponse;
import com.example.llmn.domain.Summary;
import com.example.llmn.domain.SummaryType;
import com.example.llmn.repository.SummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InsightService {

    private final SummaryRepository summaryRepository;

    @Transactional(readOnly = true)
    public InsightResponse.FindInsightHomeDTO findInsightHome() {
        List<SummaryType> types = List.of(SummaryType.PERFORMANCE, SummaryType.DAILY, SummaryType.TEND, SummaryType.RECOMMENDATION);
        List<Summary> latestSummaries = summaryRepository.findLatestSummariesByTypes(types);

        // SummaryType을 키로 하고 Summary의 content를 값으로 하는 Map 생성
        Map<SummaryType, String> summaryMap = latestSummaries.stream()
                .collect(Collectors.toMap(Summary::getSummaryType, Summary::getContent));

        return new InsightResponse.FindInsightHomeDTO(
                summaryMap.getOrDefault(SummaryType.PERFORMANCE, null),
                summaryMap.getOrDefault(SummaryType.DAILY, null),
                summaryMap.getOrDefault(SummaryType.TEND, null),
                summaryMap.getOrDefault(SummaryType.RECOMMENDATION, null)
        );
    }

    @Transactional(readOnly = true)
    public InsightResponse.FindPerformanceSummaryDTO findPerformanceSummary(Pageable pageable){
        List<Summary> performanceSummaries = summaryRepository.findSummaryByType(SummaryType.PERFORMANCE, pageable).getContent();

        List<InsightResponse.PerformanceSummaryDTO> performanceSummaryDTOS = performanceSummaries.stream()
                .map(summary -> new InsightResponse.PerformanceSummaryDTO(
                        summary.getCreatedDate(),
                        summary.getContent()
                ))
                .toList();

        return new InsightResponse.FindPerformanceSummaryDTO(performanceSummaryDTOS);
    }
}
