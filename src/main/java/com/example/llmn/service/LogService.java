package com.example.llmn.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.json.JsonData;
import com.example.llmn.controller.DTO.LogData;
import com.example.llmn.core.config.LogWebSocketHandler;
import com.example.llmn.core.utils.LogDataParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class LogService {

    private final ElasticsearchClient client;
    private final RedisService redisService;

    @Scheduled(fixedRate = 60000)  // 1분마다 실행
    public void processAndUpdateLogs() throws IOException {
        // 1. Elasticsearch에서 기존 로그 데이터 조회
        List<Map<String, Object>> logs = fetchAndProcessLogs();

        // 2. 조회한 로그 데이터를 가공
        List<Map<String, Object>> processedLogs = transformLogs(logs);

        // 3. 가공된 데이터를 Elasticsearch에서 기존 문서로 업데이트
        updateProcessedLogs(processedLogs);

        // 4. 텍스트 파일에 로그 기록
        writeLogsToFile(processedLogs);

        log.info("업데이트 완료");
    }

    @Transactional
    public List<LogData> searchLogs(Instant startTime, Instant endTime, String logLevel, String serviceName, String userId) throws IOException {
        // Elasticsearch 쿼리 생성
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                .index("docker-logs-*")
                .query(q -> {
                    BoolQuery.Builder boolQuery = new BoolQuery.Builder();

                    // 시간 범위 필터
                    boolQuery.must(m -> m.range(r -> r
                            .field("@timestamp")
                            .gte(JsonData.of(startTime.toString()))
                            .lte(JsonData.of(endTime.toString()))
                    ));

                    // 로그 레벨 필터 (필터 값이 null이 아닐 때만 추가)
                    if (logLevel != null) {
                        boolQuery.filter(f -> f.term(t -> t
                                .field("log_level")
                                .value(logLevel)
                        ));
                    }

                    // 서비스 이름 필터
                    if (serviceName != null) {
                        boolQuery.filter(f -> f.term(t -> t
                                .field("service_name")
                                .value(serviceName)
                        ));
                    }

                    // 사용자 ID 필터
                    if (userId != null) {
                        boolQuery.filter(f -> f.term(t -> t
                                .field("user_id")
                                .value(userId)
                        ));
                    }

                    // BoolQuery를 Query로 변환하여 반환
                    return q.bool(boolQuery.build());
                });

        // Elasticsearch에서 쿼리 실행
        SearchResponse<Map> response = client.search(searchBuilder.build(), Map.class);

        // 검색 결과를 LogData로 변환하여 반환
        return response.hits().hits().stream()
                .map(hit -> convertToLogData(hit.source()))
                .collect(Collectors.toList());
    }

    private LogData convertToLogData(Map<String, Object> source) {
        Map<String, Object> container = (Map<String, Object>) source.get("container");
        String containerName = container != null ? (String) container.get("name") : null;

        String timestampStr = (String) source.get("@timestamp");
        Instant timestamp = timestampStr != null ? Instant.parse(timestampStr) : null;

        String rawMessage = (String) source.get("message");
        String formattedMessage = LogDataParser.formatMessage(rawMessage);  // MongoDB 로그와 같은 형식으로 변환

        return new LogData(containerName, timestamp, formattedMessage);
    }

    private List<Map<String, Object>> fetchAndProcessLogs() throws IOException {
        // 오늘 날짜의 인덱스 이름을 생성하여 사용
        String indexName = getTodayIndexName();

        // Elasticsearch에서 변환되지 않은 로그 조회 (is_processed가 false 또는 존재하지 않는 로그)
        SearchRequest searchRequest = new SearchRequest.Builder()
                .index(indexName)
                .query(q -> q.bool(b -> b
                        .should(s -> s.term(t -> t.field("is_processed").value(false)))  // is_processed가 false인 로그
                        .should(s -> s.bool(bs -> bs.mustNot(mn -> mn.exists(e -> e.field("is_processed")))))  // is_processed 필드가 없는 로그
                ))
                .size(1000)  // 최대 1000개의 로그를 가져옴
                .build();

        SearchResponse<Map> searchResponse = client.search(searchRequest, Map.class);

        // ID와 로그 데이터 둘 다 저장
        List<Map<String, Object>> logs = searchResponse.hits().hits().stream()
                .map(hit -> {
                    Map<String, Object> log = hit.source();
                    log.put("_id", hit.id());
                    return log;
                })
                .collect(Collectors.toList());

        log.info("로그 데이터 변환 완료. 총 {}개의 로그가 변환됨.", logs.size());

        return logs;
    }

    private List<Map<String, Object>> transformLogs(List<Map<String, Object>> logs) {
        return logs.stream()
                .map(log -> {
                    // 기존 데이터 가공
                    Map<String, Object> container = (Map<String, Object>) log.get("container");
                    String serviceName = container != null ? (String) container.get("name") : "unknown_service";
                    String message = (String) log.get("message");

                    // 로그 레벨 추출
                    String logLevel = extractLogLevelFromMessage(message);

                    // 등록 서비스 목록에 추가
                    if(!redisService.isSetElementExists("service_name", serviceName)){
                        redisService.addSetElement("service_name", serviceName);
                    }

                    // 변환된 로그 데이터를 업데이트
                    log.put("log_level", logLevel);
                    log.put("service_name", serviceName);
                    log.put("user_id", "user123");
                    log.put("is_processed", true);
                    log.put("message", message != null ? message : "No message");

                    return log;
                })
                .collect(Collectors.toList());
    }

    private void updateProcessedLogs(List<Map<String, Object>> processedLogs) throws IOException {
        String indexName = getTodayIndexName();

        for (Map<String, Object> log : processedLogs) {
            String id = (String) log.remove("_id");

            UpdateRequest<Map<String, Object>, Map<String, Object>> updateRequest = new UpdateRequest.Builder<Map<String, Object>, Map<String, Object>>()
                    .index(indexName)
                    .id(id)
                    .doc(log)
                    .build();

            client.update(updateRequest, Map.class);
        }
    }

    // 로그 메시지에서 로그 레벨 추출
    private String extractLogLevelFromMessage(String message) {
        if (message == null) {
            return "UNKNOWN";
        }

        if (message.contains("INFO")) {
            return "INFO";
        } else if (message.contains("ERROR")) {
            return "ERROR";
        } else if (message.contains("WARN")) {
            return "WARN";
        }

        return "UNKNOWN";
    }

    // 오늘 날짜를 기반으로 인덱스 이름 생성
    private String getTodayIndexName() {
        LocalDate today = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd");
        return "docker-logs-" + today.format(formatter);
    }

    private void deleteOldLogs(Instant lastCollectedTime) throws IOException {
        // Elasticsearch에서 lastCollectedTime 이전의 로그 삭제
        DeleteByQueryRequest deleteRequest = DeleteByQueryRequest.of(d -> d
                .index("docker-logs-*")
                .query(q -> q.range(r -> r
                        .field("@timestamp")
                        .lte(JsonData.of(lastCollectedTime.toString()))
                ))
        );

        // 삭제 요청 실행
        client.deleteByQuery(deleteRequest);
    }

    private void writeLogsToFile(List<Map<String, Object>> logs) throws IOException {
        if (logs.isEmpty()) {
            return;
        }

        // 1. 서비스별로 로그를 그룹화
        Map<String, List<Map<String, Object>>> logsByService = logs.stream()
                .collect(Collectors.groupingBy(log -> (String) log.getOrDefault("service_name", "unknown")));

        // 2. 서비스별로 로그 파일 생성 및 기록
        for (Map.Entry<String, List<Map<String, Object>>> entry : logsByService.entrySet()) {
            String serviceName = entry.getKey();
            List<Map<String, Object>> serviceLogs = entry.getValue();

            // 현재 시간에 따라 파일 이름 생성 (서비스 이름 포함, 시 단위로 설정)
            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH").format(new Date()); // 시 단위까지 포함
            String logFileName = "logs/" + serviceName + "-log-" + timestamp + ".txt";

            // 디렉터리가 존재하는지 확인하고 없으면 생성
            Path logDirPath = Paths.get("logs");
            if (!Files.exists(logDirPath)) {
                Files.createDirectories(logDirPath);
            }

            // 파일에 기록하기 위한 BufferedWriter 생성 (1시간 동안 같은 파일에 기록)
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(Paths.get(logFileName).toFile(), true))) {
                for (Map<String, Object> logEntry : serviceLogs) {
                    String logMessage = formatLogEntry(logEntry);

                    if (logMessage != null) {
                        writer.write(logMessage);
                        writer.newLine();
                        writer.newLine();  // 가독성을 위해 빈 줄 추가
                    }
                }
            } catch (IOException e) {
                log.error("로그 파일에 기록하는 중 오류 발생", e);
                throw e;
            }
        }
    }

    private String formatLogEntry(Map<String, Object> logEntry) {
        // 필요한 로그 데이터만 추출하여 포맷팅
        Object timestamp = logEntry.get("@timestamp");
        Object logLevel = logEntry.get("log_level");
        Object message = logEntry.get("message");

        // timestamp 또는 message가 없으면 해당 로그는 무시
        if (timestamp == null || message == null) {
            return null;
        }

        // 로그 데이터를 원하는 형식으로 포맷
        return String.format("[%s] %s: %s",
                timestamp.toString(),
                logLevel != null ? logLevel.toString() : "INFO", // log_level이 없으면 기본값으로 INFO 사용
                message.toString());
    }
}
