package com.example.llm_ops;

import com.example.llm_ops.core.utils.ApiUtils;
import com.example.llm_ops.service.LogDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class LogDataController {

    private final LogDataService logDataService;

    @GetMapping("/log")
    public ResponseEntity<?> fetchLogs() throws IOException {
        logDataService.fetchLogs();
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }
}
