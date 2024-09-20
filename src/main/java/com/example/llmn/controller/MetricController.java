package com.example.llmn.controller;

import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.controller.DTO.ProjectResponse;
import com.example.llmn.core.security.CustomUserDetails;
import com.example.llmn.core.utils.ApiUtils;
import com.example.llmn.core.utils.SSHCommandExecutor;
import com.example.llmn.core.utils.SshUtils;
import com.example.llmn.service.MetricService;
import com.example.llmn.service.SSHService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
            // 동일한 SSH 세션을 사용하여 명령어 실행
            String result = sshService.executeCommand(command);

            // 명령어 실행 결과를 JSON으로 응답
            return ResponseEntity.ok(Map.of("result", result));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("명령어 실행 중 에러 발생");
        }
    }
}
