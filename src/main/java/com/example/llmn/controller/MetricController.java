package com.example.llmn.controller;

import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.core.utils.ApiUtils;
import com.example.llmn.service.MetricService;
import com.example.llmn.service.SSHService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class MetricController {

    private final MetricService metricService;
    private final SSHService sshService;
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

    @PostMapping("/command")
    public ResponseEntity<?> executeCommand(@RequestBody Map<String, String> request) {
        String command = request.get("command");

        try {
            System.out.println("comend:" + command);
            String result = sshService.executeCommandInShell(command);
            return ResponseEntity.ok(Map.of("result", result));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("명령어 실행 중 에러 발생");
        }
    }
}
