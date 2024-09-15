package com.example.llmn.controller;

import com.example.llmn.controller.DTO.InsightResponse;
import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.core.utils.ApiUtils;
import com.example.llmn.domain.SummaryType;
import com.example.llmn.service.InsightService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class InsightController {

    private final InsightService insightService;
    private static final String SORT_BY_DATE = "createdDate";

    @GetMapping("/insight")
    public ResponseEntity<?> findInsightList() {
        InsightResponse.FindInsightHomeDTO responseDTO = insightService.findInsightList();
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/insight/performance")
    public ResponseEntity<?> findPerformanceSummary(@PageableDefault(size = 5, sort = SORT_BY_DATE, direction = Sort.Direction.DESC)Pageable pageable) {
        List<InsightResponse.SummaryDTO> summaryDTOS = insightService.findSummaryByType(SummaryType.PERFORMANCE, pageable);
        InsightResponse.FindPerformanceSummaryDTO responseDTO = new InsightResponse.FindPerformanceSummaryDTO(summaryDTOS);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/insight/daily")
    public ResponseEntity<?> findDailySummary(@PageableDefault(size = 5, sort = SORT_BY_DATE, direction = Sort.Direction.DESC)Pageable pageable) {
        List<InsightResponse.SummaryDTO> summaryDTOS = insightService.findSummaryByType(SummaryType.DAILY, pageable);
        InsightResponse.FindDailySummaryDTO responseDTO = new InsightResponse.FindDailySummaryDTO(summaryDTOS);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }
}
