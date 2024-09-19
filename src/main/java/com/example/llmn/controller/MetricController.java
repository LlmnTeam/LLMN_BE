package com.example.llmn.controller;

import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.controller.DTO.ProjectResponse;
import com.example.llmn.core.security.CustomUserDetails;
import com.example.llmn.core.utils.ApiUtils;
import com.example.llmn.service.MetricService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class MetricController {

    private final MetricService metricService;
    private static final int METRIC_HISTORY_PREVIOUS_HOUR = 24;

    @GetMapping("/metrics")
    public ResponseEntity<?> findProjectList() {
        MetricResponse.FindMetricHistoryDTO responseDTO = metricService.findMetricHistory(METRIC_HISTORY_PREVIOUS_HOUR);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/metrics/remote")
    public ResponseEntity<?> collectRemoteMetrics(@RequestParam String privateKeyPath,
                                                  @RequestParam String host,
                                                  @RequestParam String username)  {
        Map<String, Object> stringObjectMap = metricService.collectRemoteMetrics(privateKeyPath, host, username);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, stringObjectMap));
    }
}
