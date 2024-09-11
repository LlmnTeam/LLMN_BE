package com.example.llmn.controller;

import com.example.llmn.controller.DTO.LogData;
import com.example.llmn.core.utils.ApiUtils;
import com.example.llmn.service.LogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class LogController {

    private final LogService logService;

    @PatchMapping("/log")
    public ResponseEntity<?> fetchLogs() throws IOException {
        // logService.fetchLogs();
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @GetMapping("/log")
    public ResponseEntity<?> searchLogs(@RequestParam Instant startTime, @RequestParam Instant endTime, @RequestParam(required = false) String logLevel, @RequestParam(required = false) String serviceName) throws IOException {
        List<LogData> response = logService.searchLogs(startTime, endTime, logLevel, serviceName);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, response));
    }
}
