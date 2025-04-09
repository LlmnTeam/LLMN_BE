package com.example.llmn.domain.insight;

import com.example.llmn.common.utils.DateTimeUtils;
import com.example.llmn.domain.insight.model.FindInsightHomeRes;
import com.example.llmn.domain.insight.model.FindSummaryRes;
import com.example.llmn.domain.summary.Summary;
import com.example.llmn.domain.summary.SummaryType;
import com.example.llmn.domain.summary.SummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InsightService {

    private final SummaryRepository summaryRepository;

    public FindInsightHomeRes findInsightList() {
        List<SummaryType> types = List.of(SummaryType.PERFORMANCE, SummaryType.DAILY, SummaryType.TEND, SummaryType.RECOMMENDATION);
        Map<SummaryType, Summary> summaryMap = getLatestSummaryMapByTypes(types);

        return new FindInsightHomeRes(
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

    public FindSummaryRes findSummaryByType(SummaryType type, Long userId, Pageable pageable) {
        List<Summary> summaries = summaryRepository.findByType(type, userId, pageable).getContent();
        return FindSummaryRes.from(summaries);
    }

    private Map<SummaryType, Summary> getLatestSummaryMapByTypes(List<SummaryType> types) {
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
}
