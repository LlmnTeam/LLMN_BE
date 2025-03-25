package com.example.llmn.domain.insight;

import com.example.llmn.security.userdetails.CustomUserDetails;
import com.example.llmn.common.utils.ApiUtils;
import com.example.llmn.domain.summary.SummaryType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    public ResponseEntity<?> findPerformanceSummary(@PageableDefault(size = 5, sort = SORT_BY_DATE, direction = Sort.Direction.DESC) Pageable pageable,
                                                    @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<InsightResponse.SummaryDTO> summaryDTOS = insightService.findSummaryByType(SummaryType.PERFORMANCE, userDetails.getUser().getId(), pageable);
        InsightResponse.FindSummaryDTO responseDTO = new InsightResponse.FindSummaryDTO(summaryDTOS);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/insight/daily")
    public ResponseEntity<?> findDailySummary(@PageableDefault(size = 5, sort = SORT_BY_DATE, direction = Sort.Direction.DESC) Pageable pageable,
                                              @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<InsightResponse.SummaryDTO> summaryDTOS = insightService.findSummaryByType(SummaryType.DAILY, userDetails.getUser().getId(), pageable);
        InsightResponse.FindSummaryDTO responseDTO = new InsightResponse.FindSummaryDTO(summaryDTOS);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/insight/trend")
    public ResponseEntity<?> findTrendSummary(@PageableDefault(size = 5, sort = SORT_BY_DATE, direction = Sort.Direction.DESC) Pageable pageable,
                                              @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<InsightResponse.SummaryDTO> summaryDTOS = insightService.findSummaryByType(SummaryType.TEND, userDetails.getUser().getId(), pageable);
        InsightResponse.FindSummaryDTO responseDTO = new InsightResponse.FindSummaryDTO(summaryDTOS);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/insight/recommendation")
    public ResponseEntity<?> findRecommendation(@PageableDefault(size = 5, sort = SORT_BY_DATE, direction = Sort.Direction.DESC) Pageable pageable,
                                                @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<InsightResponse.SummaryDTO> summaryDTOS = insightService.findSummaryByType(SummaryType.RECOMMENDATION, userDetails.getUser().getId(), pageable);
        InsightResponse.FindSummaryDTO responseDTO = new InsightResponse.FindSummaryDTO(summaryDTOS);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }
}
