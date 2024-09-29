package com.example.llmn.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.json.JsonData;
import com.example.llmn.controller.DTO.LogDataDTO;
import com.example.llmn.core.config.ElasticsearchConfig;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.core.utils.LogDataParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogService {

    private final ElasticsearchConfig elasticsearchConfig;

    @Value("${elasticsearch.host}")
    private String ELASTIC_SEARCH_HOST;

    private static final String LOGS_DIRECTORY = "logs";
    private static final String LOG_LEVEL_INFO = "INFO";
    private static final String LOG_LEVEL_WARN = "WARN";
    private static final String LOG_LEVEL_ERROR = "ERROR";
    private static final String LOG_LEVEL_UNKNOWN = "UNKNOWN";
    private static final String LOG_KEY_TIMESTAMP = "@timestamp";
    private static final String LOG_KEY_LEVEL = "log_level";
    private static final String LOG_KEY_CONTAINER_NAME = "container_name";
    private static final String LOG_KEY_CONTAINER = "container";
    private static final String LOG_KEY_MESSAGE = "message";
    private static final String LOG_KEY_PROCESSED = "is_processed";
    private static final String LOG_KEY_ID = "_id";
    private static final String CONTAINER_KEY_NAME = "name";
    private static final String UNKNOWN_CONTAINER = "unknown_container";
    private static final String NO_MESSAGE = "No message";

    @Scheduled(fixedRate = 60000)  // 1분마다 실행
    public void processAndUpdateLogs() throws IOException {
        // 1. Elasticsearch에서 로그 데이터를 검색
        SearchResponse<Map> searchResponse = searchFromElasticSearch();

        // 2. 검색 결과를 맵으로 변환
        List<Map<String, Object>> logMaps = convertResponseToMap(searchResponse);
        log.info("로그 데이터 변환 완료. 총 {}개의 로그가 변환됨.", logMaps.size());

        // 2. map의 필드 업데이트 (원하는 형태로)
        List<Map<String, Object>> updatedLogMaps = updateLogFields(logMaps);

        // 3. 필드 업데이트 한 데이터를 Elasticsearch에도 반영
        updateToElasticSearch(updatedLogMaps);

        // 4. .txt 파일로도 로그 저장
        saveLogsToFile(updatedLogMaps);

        log.info("업데이트 완료");
    }

    public List<LogDataDTO> searchLogList(Instant startTime, Instant endTime, String logLevel, String containerName) {
        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(ELASTIC_SEARCH_HOST);

            // Elasticsearch 쿼리 생성
            SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                    .index("docker-logs-*")
                    .query(q -> {
                        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

                        // 시간 범위 필터
                        boolQuery.must(m -> m.range(r -> r
                                .field(LOG_KEY_TIMESTAMP)
                                .gte(JsonData.of(startTime.toString()))
                                .lte(JsonData.of(endTime.toString()))
                        ));

                        // 로그 레벨 필터 (필터 값이 null이 아닐 때만 추가)
                        if (logLevel != null) {
                            boolQuery.filter(f -> f.term(t -> t
                                    .field(LOG_KEY_LEVEL)
                                    .value(logLevel)
                            ));
                        }

                        // 서비스 이름 필터
                        if (containerName != null) {
                            boolQuery.filter(f -> f.term(t -> t
                                    .field(LOG_KEY_CONTAINER_NAME)
                                    .value(containerName)
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

    public String searchLogInStr(Instant startTime, Instant endTime, String containerName) {
        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(ELASTIC_SEARCH_HOST);

            // Elasticsearch 쿼리 생성
            SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                    .index("docker-logs-*")
                    .query(q -> {
                        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

                        // 시간 범위 필터
                        boolQuery.must(m -> m.range(r -> r
                                .field(LOG_KEY_TIMESTAMP)
                                .gte(JsonData.of(startTime.toString()))
                                .lte(JsonData.of(endTime.toString()))
                        ));

                        // 서비스 이름 필터
                        if (containerName != null) {
                            boolQuery.filter(f -> f.term(t -> t
                                    .field(LOG_KEY_CONTAINER_NAME)
                                    .value(containerName)
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

    public List<String> findLogFileList() {
        // logs 디렉토리 경로
        Path logDirPath = Paths.get(LOGS_DIRECTORY);

        // 로그 파일들이 저장된 디렉토리가 존재하는지 확인
        if (!Files.exists(logDirPath) || !Files.isDirectory(logDirPath)) {
            throw new CustomException(ExceptionCode.LOG_DIRECTORY_NOT_FOUND);
        }

        // 디렉토리 내의 모든 파일 목록을 가져오고, ".txt" 확장자를 가진 파일들만 필터링
        try (Stream<Path> filePathStream = Files.list(logDirPath)) {
            List<String> fileNames = filePathStream
                    .filter(Files::isRegularFile) // 일반 파일만 가져옴
                    .filter(path -> path.toString().endsWith(".txt")) // .txt 파일만 가져옴
                    .map(path -> path.getFileName().toString())
                    .toList();

            return fileNames;
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

    private LogDataDTO convertToLogData(Map<String, Object> source) {
        String containerName = Optional.ofNullable((String) source.get(LOG_KEY_CONTAINER_NAME))
                .orElse(UNKNOWN_CONTAINER);

        String timestampInStr = (String) source.get(LOG_KEY_TIMESTAMP);
        Instant timestamp = timestampInStr != null ? Instant.parse(timestampInStr) : null;

        String rawMessage = (String) source.get(LOG_KEY_MESSAGE);
        String formattedMessage = rawMessage != null ? LogDataParser.formatMessage(rawMessage) : NO_MESSAGE;

        boolean isProcessed = (boolean) source.get(LOG_KEY_PROCESSED);

        String logLevel = Optional.ofNullable((String) source.get(LOG_KEY_LEVEL))
                .orElse(LOG_LEVEL_UNKNOWN);

        return new LogDataDTO(containerName, timestamp, formattedMessage, isProcessed, logLevel);
    }

    private String convertToString(Map<String, Object> source) {
        String rawMessage = (String) source.get(LOG_KEY_MESSAGE);
        return rawMessage != null ? rawMessage : "";
    }

    private SearchResponse<Map> searchFromElasticSearch() throws IOException {
        // 오늘 날짜의 인덱스 이름을 생성하여 사용
        String indexName = getTodayIndexName();

        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(ELASTIC_SEARCH_HOST);

            // is_processed가 false인 데이터 검색
            SearchRequest searchRequest = new SearchRequest.Builder()
                    .index(indexName)
                    .query(q -> q.bool(b -> b
                            .should(s -> s.term(t -> t.field(LOG_KEY_PROCESSED).value(false)))  // is_processed가 false인 로그
                            .should(s -> s.bool(bs -> bs.mustNot(mn -> mn.exists(e -> e.field(LOG_KEY_PROCESSED)))))  // is_processed 필드가 없는 로그
                    ))
                    .size(1000)  // 최대 1000개의 로그를 가져옴
                    .build();

            return client.search(searchRequest, Map.class);
        } catch (ElasticsearchException e) {
            // 인덱스가 없을 경우 생성
            createIndexIfNotExists(indexName);
            return new SearchResponse.Builder<Map>().build();
        }
    }

    private List<Map<String, Object>> convertResponseToMap(SearchResponse<Map> searchResponse){
        return searchResponse
                .hits().hits().stream()
                .map(hit -> {
                    Map<String, Object> logMap = hit.source();
                    logMap.put(LOG_KEY_ID, hit.id());
                    return logMap;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> updateLogFields(List<Map<String, Object>> logs) {
        return logs.stream()
                .map(log -> {
                    // 맵에서 기존 데이터 추출
                    Map<String, Object> container = (Map<String, Object>) log.get(LOG_KEY_CONTAINER);
                    String containerName = container != null ? (String) container.get(CONTAINER_KEY_NAME) : UNKNOWN_CONTAINER;
                    String message = Optional.ofNullable((String) log.get(LOG_KEY_MESSAGE))
                            .orElse(NO_MESSAGE);

                    // 로그 레벨 추출
                    String logLevel = extractLogLevel(message);

                    // 맵에 필드 추가
                    log.put(LOG_KEY_LEVEL, logLevel);
                    log.put(LOG_KEY_CONTAINER_NAME, containerName);
                    log.put(LOG_KEY_PROCESSED, true);
                    log.put(LOG_KEY_MESSAGE, message);

                    // container 필드는 삭제
                    log.remove(LOG_KEY_CONTAINER);

                    return log;
                })
                .collect(Collectors.toList());
    }

    private void updateToElasticSearch(List<Map<String, Object>> updatedLogMaps) throws IOException {
        ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(ELASTIC_SEARCH_HOST);

        String indexName = getTodayIndexName();

        for (Map<String, Object> logMap : updatedLogMaps) {
            String id = (String) logMap.remove(LOG_KEY_ID);

            UpdateRequest<Map<String, Object>, Map<String, Object>> updateRequest = new UpdateRequest.Builder<Map<String, Object>, Map<String, Object>>()
                    .index(indexName)
                    .id(id)
                    .doc(logMap)
                    .build();

            client.update(updateRequest, Map.class);
        }
    }

    // 로그 메시지에서 로그 레벨 추출
    private String extractLogLevel(String message) {
        return (message == null) ? LOG_LEVEL_UNKNOWN :
                Stream.of(LOG_LEVEL_INFO, LOG_LEVEL_ERROR, LOG_LEVEL_WARN)
                        .filter(message::contains)
                        .findFirst()
                        .orElse(LOG_LEVEL_INFO);
    }

    // 오늘 날짜를 기반으로 인덱스 이름 생성
    private String getTodayIndexName() {
        LocalDate today = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd");
        return "docker-logs-" + today.format(formatter);
    }

    private void deleteOldLogs(Instant lastCollectedTime) throws IOException {
        ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(ELASTIC_SEARCH_HOST);

        // Elasticsearch에서 lastCollectedTime 이전의 로그 삭제
        DeleteByQueryRequest deleteRequest = DeleteByQueryRequest.of(d -> d
                .index("docker-logs-*")
                .query(q -> q.range(r -> r
                        .field(LOG_KEY_TIMESTAMP)
                        .lte(JsonData.of(lastCollectedTime.toString()))
                ))
        );

        // 삭제 요청 실행
        client.deleteByQuery(deleteRequest);
    }

    private void saveLogsToFile(List<Map<String, Object>> logs) {
        if (logs.isEmpty()) {
            return;
        }

        // 1. 서비스별로 로그를 그룹화
        Map<String, List<Map<String, Object>>> logsByContainerName = logs.stream()
                .collect(Collectors.groupingBy(log -> (String) log.getOrDefault(LOG_KEY_CONTAINER_NAME, UNKNOWN_CONTAINER)));

        // 2. 로그를 저장할 디렉토리가 없으면 생성
        createLogDirectoryIfNotExist();

        // 3. 로그 파일 저장
        Date now = new Date();
        String timestampForTitle = new SimpleDateFormat("yyyy-MM-dd_HH").format(now);
        String timestampForText = new SimpleDateFormat("yyyy-MM-dd_HH:mm").format(now);

        logsByContainerName.forEach((containerName, logMaps) -> {
            String fileTitle = String.format("logs/%s-log-%s.txt", containerName, timestampForTitle);
            writeBufferAsFile(fileTitle, logMaps, timestampForText);
        });
    }

    private void createLogDirectoryIfNotExist() {
        try {
            Path logDirPath = Paths.get(LOGS_DIRECTORY);
            if (!Files.exists(logDirPath)) {
                Files.createDirectories(logDirPath);
            }
        } catch (IOException e){
            log.error("로그 파일 저장을 위한 디렉토리 생성 실패.", e);
        }
    }

    private void writeBufferAsFile(String fileTitle, List<Map<String, Object>> logMaps, String timestamp) {
        // 파일에 기록하기 위한 BufferedWriter 생성
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileTitle, true))) {
            for (Map<String, Object> logMap : logMaps) {
                String logMessage = formatLogToStr(logMap, timestamp);

                if (logMessage != null) {
                    writer.write(logMessage);
                    writer.newLine();
                    writer.newLine();  // 가독성을 위해 빈 줄 추가
                }
            }
        } catch (IOException e) {
            log.error("로그 파일에 기록하는 중 오류 발생", e);
        }
    }

    private String formatLogToStr(Map<String, Object> logMap, String timestamp) {
        // 필요한 로그 데이터만 추출하여 포맷팅
        Object message = logMap.get(LOG_KEY_MESSAGE);

        if (message == null) {
            return null;
        }


        // 로그 데이터를 원하는 형식으로 포맷
        return String.format("[%s]\n%s",
                timestamp,
                message.toString());
    }

    private void createIndexIfNotExists(String indexName)  {
        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(ELASTIC_SEARCH_HOST);

            CreateIndexRequest createIndexRequest = new CreateIndexRequest.Builder()
                    .index(indexName)
                    .mappings(m -> m
                            .properties(LOG_KEY_PROCESSED, p -> p.boolean_(b -> b))
                            .properties(LOG_KEY_TIMESTAMP, p -> p.date(d -> d))
                            .properties(LOG_KEY_LEVEL, p -> p.keyword(k -> k))
                            .properties(LOG_KEY_CONTAINER_NAME, p -> p.keyword(k -> k))
                            .properties(LOG_KEY_MESSAGE, p -> p.text(t -> t))
                    )
                    .build();

            client.indices().create(createIndexRequest);
            log.info("인덱스 {} 생성 완료", indexName);
        } catch (IOException e) {
            log.info("ElasticSearch 인덱스 생성 실패!");
        }
    }
}
