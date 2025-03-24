package com.example.llmn.service;

import com.example.llmn.controller.DTO.InsightResponse;
import com.example.llmn.core.utils.DateTimeUtils;
import com.example.llmn.domain.Summary;
import com.example.llmn.domain.SummaryType;
import com.example.llmn.repository.SummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.example.llmn.core.utils.DateTimeUtils.formatLocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InsightService {

    private final SummaryRepository summaryRepository;

    public InsightResponse.FindInsightHomeDTO findInsightList() {
        List<SummaryType> types = List.of(SummaryType.PERFORMANCE, SummaryType.DAILY, SummaryType.TEND, SummaryType.RECOMMENDATION);
        Map<SummaryType, Summary> summaryMap = getSummaryMapByTypes(types);

        return new InsightResponse.FindInsightHomeDTO(
                extractContent(summaryMap, SummaryType.PERFORMANCE),
                extractUpdatedDate(summaryMap, SummaryType.PERFORMANCE),
                extractContent(summaryMap, SummaryType.DAILY),
                extractUpdatedDate(summaryMap, SummaryType.DAILY),
                extractContent(summaryMap, SummaryType.TEND),
                extractUpdatedDate(summaryMap, SummaryType.TEND),
                extractContent(summaryMap, SummaryType.RECOMMENDATION),
                extractUpdatedDate(summaryMap, SummaryType.RECOMMENDATION)
        );
    }

    public List<InsightResponse.SummaryDTO> findSummaryByType(SummaryType type, Long userId, Pageable pageable){
        List<Summary> performanceSummaries = summaryRepository.findByType(type, userId, pageable).getContent();

        return performanceSummaries.stream()
                .map(this::createSummaryDTO)
                .toList();
    }

    private Map<SummaryType, Summary> getSummaryMapByTypes(List<SummaryType> types) {
        return summaryRepository.findLatestByTypes(types).stream()
                .collect(Collectors.toMap(Summary::getSummaryType, summary -> summary));
    }

    private String extractContent(Map<SummaryType, Summary> summaryMap, SummaryType type) {
        return Optional.ofNullable(summaryMap.get(type))
                .map(Summary::getContent)
                .orElse("");
    }

    private String extractUpdatedDate(Map<SummaryType, Summary> summaryMap, SummaryType type) {
        return Optional.ofNullable(summaryMap.get(type))
                .map(Summary::getUpdatedDate)
                .map(DateTimeUtils::formatLocalDateTime)
                .orElse("");
    }

    private InsightResponse.SummaryDTO createSummaryDTO(Summary summary){
        return new InsightResponse.SummaryDTO(
                summary.getId(),
                formatLocalDateTime(summary.getCreatedDate()),
                summary.getContent(),
                summary.isChecked());
    }
}
