package com.example.llmn.domain.log;

import com.example.llmn.common.utils.ApiUtils;
import com.example.llmn.domain.log.model.LogDataDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

import static com.example.llmn.common.utils.FileUtils.LOGS_DIRECTORY;
import static com.example.llmn.common.utils.FileUtils.getFileAsResource;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class LogController {

    private final LogService logService;

    @GetMapping("/logs")
    public ResponseEntity<?> searchLogs(@RequestParam Instant startTime,
                                        @RequestParam Instant endTime,
                                        @RequestParam(required = false) String logLevel,
                                        @RequestParam(required = false) String serviceName,
                                        @RequestParam String elasticSearchHost) {
        List<LogDataDTO> responseDTO = logService.searchLog(startTime, endTime, logLevel, serviceName, elasticSearchHost);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/logs/{logFileName}/download")
    public ResponseEntity<Resource> downloadLogFile(@PathVariable String logFileName) {
        Resource resource = getFileAsResource(LOGS_DIRECTORY, logFileName);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MediaType.TEXT_PLAIN_VALUE))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + logFileName + "\"")
                .body(resource);
    }
}
