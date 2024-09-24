package com.example.llmn.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.json.JsonData;
import com.example.llmn.controller.DTO.LogData;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.core.utils.LogDataParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
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
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogService {

    private final ElasticsearchClient client;
    private final RedisService redisService;
    private static final String LOGS_DIRECTORY = "logs";

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

    public List<LogData> searchLogList(Instant startTime, Instant endTime, String logLevel, String serviceName) {
        try {
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

                        // BoolQuery를 Query로 변환하여 반환
                        return q.bool(boolQuery.build());
                    });

            // Elasticsearch에서 쿼리 실행
            SearchResponse<Map> response = client.search(searchBuilder.build(), Map.class);

            // 검색 결과를 LogData로 변환하여 반환
            return response.hits().hits().stream()
                    .map(hit -> convertToLogData(hit.source()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new CustomException(ExceptionCode.ELASTIC_SEARCH_ERROR);
        }
    }

    public String searchLogInStr(Instant startTime, Instant endTime, String serviceName) {
        try {
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

                        // 서비스 이름 필터
                        if (serviceName != null) {
                            boolQuery.filter(f -> f.term(t -> t
                                    .field("service_name")
                                    .value(serviceName)
                            ));
                        }

                        // BoolQuery를 Query로 변환하여 반환
                        return q.bool(boolQuery.build());
                    });

            // Elasticsearch에서 쿼리 실행
            SearchResponse<Map> response = client.search(searchBuilder.build(), Map.class);

            List<String> messages = response.hits().hits().stream()
                    .map(hit -> convertToString(hit.source()))
                    .toList();

            return String.join("\n", messages);  // 각 로그 사이에 줄 바꿈 추가
        } catch (IOException e) {
            throw new CustomException(ExceptionCode.ELASTIC_SEARCH_ERROR);
        }
    }

    public String searchRecentLogInStr(String serviceName, Long cnt) {
        try {
            // Elasticsearch 쿼리 생성
            SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                    .index("docker-logs-*")
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t
                                    .field("service_name")
                                    .value(serviceName)
                            ))
                    ))
                    .sort(s -> s
                            .field(f -> f
                                    .field("@timestamp")  // 타임스탬프 기준으로 정렬
                                    .order(SortOrder.Desc)  // 가장 최근 순으로 정렬 (내림차순)
                            )
                    )
                    .size(cnt.intValue()); // 최대 cnt 개만큼의 로그를 가져옴

            SearchResponse<Map> response = client.search(searchBuilder.build(), Map.class);

            // 검색 결과를 LogData로 변환한 후 역순으로 정렬하여 반환
            List<String> messages = response.hits().hits().stream()
                    .map(hit -> convertToString(hit.source()))
                    .collect(Collectors.toList());

            // 내림차순으로 받아온 데이터를 다시 역순으로 뒤집어 가장 오래된 로그가 먼저 오도록 함
            Collections.reverse(messages);

            return String.join("\n\n", messages);  // 각 로그 사이에 줄 바꿈 추가
        } catch (IOException e) {
            throw new CustomException(ExceptionCode.ELASTIC_SEARCH_ERROR);
        }
    }

    public List<String> findLogFileList() {
        // logs 디렉토리 경로
        Path logDirPath = Paths.get(LOGS_DIRECTORY);

        // 로그 파일들이 저장된 디렉토리가 존재하는지 확인
        if (!Files.exists(logDirPath) || !Files.isDirectory(logDirPath)) {
            throw new CustomException(ExceptionCode.LOG_DIRECTORY_NOT_FOUND);
        }

        // 디렉토리 내의 모든 파일 목록을 가져오고, ".txt" 확장자를 가진 파일들만 필터링
        try (Stream<Path> filePathStream = Files.list(logDirPath)) {
            return filePathStream
                    .filter(Files::isRegularFile) // 일반 파일만 필터링
                    .filter(path -> path.toString().endsWith(".txt")) // .txt 파일만 가져옴
                    .map(path -> path.getFileName().toString()) // 파일 이름만 가져옴
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new CustomException(ExceptionCode.LOG_FILE_LIST_READ_FAIL);
        }
    }

    public String readLogFile(String fileName) {
        // 로그 파일은 logs 디렉토리에 위치
        Path logFilePath = Paths.get(LOGS_DIRECTORY, fileName);

        // 파일이 존재하는지 확인
        if (!Files.exists(logFilePath)) {
            throw new CustomException(ExceptionCode.LOG_FILE_NOT_FOUND);
        }

        // 파일 내용을 읽어서 하나의 문자열로 변환
        try {
            List<String> lines = Files.readAllLines(logFilePath);  // 파일의 모든 줄을 읽음
            return String.join("\n\n", lines);  // 줄 단위로 합쳐서 하나의 문자열로 반환 (각 줄을 \n으로 구분)
        } catch (IOException e) {
            throw new CustomException(ExceptionCode.LOG_FILE_READ_FAIL);
        }
    }

    public Resource getLogFileAsResource(String fileName) throws IOException {
        Path logFilePath = Paths.get(LOGS_DIRECTORY, fileName);

        // 파일이 존재하는지 확인
        if (!Files.exists(logFilePath)) {
            throw new CustomException(ExceptionCode.LOG_FILE_NOT_FOUND);
        }

        // 파일을 Resource 객체로 변환
        return new UrlResource(logFilePath.toUri());
    }

    private LogData convertToLogData(Map<String, Object> source) {
        Map<String, Object> container = (Map<String, Object>) source.get("container");
        String serviceName = container != null ? (String) container.get("name") : null;

        String timestampStr = (String) source.get("@timestamp");
        Instant timestamp = timestampStr != null ? Instant.parse(timestampStr) : null;

        String rawMessage = (String) source.get("message");
        String formattedMessage = rawMessage != null ? LogDataParser.formatMessage(rawMessage) : "";

        boolean isProcessed = (boolean) source.get("is_processed");

        String logLevel = source.get("log_level") != null ? (String) source.get("log_level") : "UNKNOWN";

        return new LogData(serviceName, timestamp, formattedMessage, isProcessed, logLevel);
    }

    private String convertToString(Map<String, Object> source) {
        String rawMessage = (String) source.get("message");
        return rawMessage != null ? rawMessage : "";
    }

    private List<Map<String, Object>> fetchAndProcessLogs() throws IOException {
        // 오늘 날짜의 인덱스 이름을 생성하여 사용
        String indexName = getTodayIndexName();
        List<Map<String, Object>> logs = new ArrayList<>();

        try {
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
            logs = searchResponse.hits().hits().stream()
                    .map(hit -> {
                        Map<String, Object> log = hit.source();
                        log.put("_id", hit.id());
                        return log;
                    })
                    .collect(Collectors.toList());

            log.info("로그 데이터 변환 완료. 총 {}개의 로그가 변환됨.", logs.size());
        } catch (ElasticsearchException e) { // 인덱스가 없을 경우 생성
            createIndexIfNotExists(indexName);
        }

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

    private void createIndexIfNotExists(String indexName) throws IOException {
        try {
            CreateIndexRequest createIndexRequest = new CreateIndexRequest.Builder()
                    .index(indexName)
                    .mappings(m -> m // 기본적으로 사용할 필드 매핑 정의
                            .properties("is_processed", p -> p.boolean_(b -> b))
                            .properties("@timestamp", p -> p.date(d -> d))
                            .properties("log_level", p -> p.keyword(k -> k))
                            .properties("service_name", p -> p.keyword(k -> k))
                            .properties("message", p -> p.text(t -> t))
                    )
                    .build();
            client.indices().create(createIndexRequest);

            log.info("인덱스 {} 생성 완료", indexName);
        } catch (ElasticsearchException e) {
            throw new IOException("Elasticsearch 인덱스 생성 중 오류 발생", e);
        }
    }
}
