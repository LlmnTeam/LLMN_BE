package com.example.llmn.domain.insight;

import com.example.llmn.domain.insight.model.FindInsightHomeRes;
import com.example.llmn.domain.insight.model.FindSummaryRes;
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

import static com.example.llmn.common.constants.GlobalConstants.SORT_BY_DATE;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class InsightController {

    private final InsightService insightService;

    @GetMapping("/insight")
    public ResponseEntity<?> findInsightList() {
        FindInsightHomeRes responseDTO = insightService.findInsightList();
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/insight/performance")
    public ResponseEntity<?> findPerformanceSummary(@PageableDefault(size = 5, sort = SORT_BY_DATE, direction = Sort.Direction.DESC) Pageable pageable,
                                                    @AuthenticationPrincipal CustomUserDetails userDetails) {
        FindSummaryRes responseDTO = insightService.findSummaryByType(SummaryType.PERFORMANCE, userDetails.getUser().getId(), pageable);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/insight/daily")
    public ResponseEntity<?> findDailySummary(@PageableDefault(size = 5, sort = SORT_BY_DATE, direction = Sort.Direction.DESC) Pageable pageable,
                                              @AuthenticationPrincipal CustomUserDetails userDetails) {
        FindSummaryRes responseDTO = insightService.findSummaryByType(SummaryType.DAILY, userDetails.getUser().getId(), pageable);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/insight/trend")
    public ResponseEntity<?> findTrendSummary(@PageableDefault(size = 5, sort = SORT_BY_DATE, direction = Sort.Direction.DESC) Pageable pageable,
                                              @AuthenticationPrincipal CustomUserDetails userDetails) {
        FindSummaryRes responseDTO = insightService.findSummaryByType(SummaryType.TEND, userDetails.getUser().getId(), pageable);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/insight/recommendation")
    public ResponseEntity<?> findRecommendation(@PageableDefault(size = 5, sort = SORT_BY_DATE, direction = Sort.Direction.DESC) Pageable pageable,
                                                @AuthenticationPrincipal CustomUserDetails userDetails) {
        FindSummaryRes responseDTO = insightService.findSummaryByType(SummaryType.RECOMMENDATION, userDetails.getUser().getId(), pageable);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }
}
