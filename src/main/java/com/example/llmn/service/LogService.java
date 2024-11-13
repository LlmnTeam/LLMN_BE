package com.example.llmn.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.json.JsonData;
import com.example.llmn.controller.DTO.LogDataDTO;
import com.example.llmn.core.config.ElasticsearchConfig;
import com.example.llmn.core.utils.LogDataParser;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.repository.SshInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.example.llmn.core.utils.FileUtils.getFileList;
import static com.example.llmn.core.utils.FileUtils.readFileAsString;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogService {

    private final ElasticsearchConfig elasticsearchConfig;
    private final SshInfoRepository sshInfoRepository;

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
    private static final String BLANK_STRING = "";
    private static final String LOG_FORMAT = "[%s]\n%s";
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH");
    private static final DateTimeFormatter formatterWithMinute = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm");

    @Scheduled(fixedRate = 60000)  // 1분마다 실행
    public void processAndUpdateLogs() {
        List<SshInfo> sshInfos = sshInfoRepository.findAll();

        for(SshInfo sshInfo : sshInfos) {
            // 1. Elasticsearch에서 로그 데이터를 검색
            SearchResponse<Map> searchResponse = searchFromElasticSearch(sshInfo.getRemoteHost());
            if (searchResponse == null) {
                log.warn("Elasticsearch 응답이 null입니다. 검색에 실패했습니다.");
                return;
            }

            // 2. 검색 결과를 맵으로 변환
            List<Map<String, Object>> logMaps = convertResponseToMap(searchResponse);

            // 2. map의 필드 원하는 형태로 재조립
            List<Map<String, Object>> refinedLogMaps = refineLogFields(logMaps);

            // 3. 필드 업데이트 한 데이터를 Elasticsearch에도 반영
            updateToElasticSearch(refinedLogMaps, sshInfo.getRemoteHost());

            // 4. .txt 파일로도 로그 저장
            saveLogsToFile(refinedLogMaps, sshInfo.getId());
        }
    }

    public List<LogDataDTO> searchLogData(Instant startTime, Instant endTime, String logLevel, String containerName, String elasticSearchHost) {
        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);

            // Elasticsearch 쿼리 생성 후 실행
            SearchRequest searchRequest = buildSearchRequestQuery(startTime, endTime, logLevel, containerName);
            SearchResponse<Map> response = client.search(searchRequest, Map.class);

            // 검색 결과를 LogData로 변환하여 반환
            return response.hits().hits().stream()
                    .map(hit -> convertToLogData(hit.source()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.info("<ElasticSearch> "+ containerName +" 어플리케이션에 대해 검색 실패");
            return new ArrayList<>();
        }
    }

    public String searchLogInStr(Instant startTime, Instant endTime, String logLevel, String containerName, String elasticSearchHost) {
        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);

            SearchRequest searchRequest = buildSearchRequestQuery(startTime, endTime, logLevel, containerName);
            SearchResponse<Map> response = client.search(searchRequest, Map.class);

            List<String> logContents = response.hits().hits().stream()
                    .map(hit -> convertToString(hit.source()))
                    .toList();

            return String.join("\n", logContents);  // 각 로그 사이에 줄 바꿈 추가
        } catch (IOException e) {
            log.info("<ElasticSearch> " + containerName + " 어플리케이션에 대해 검색 실패");
            return "";
        }
    }

    public String getLogWithin30Minutes(String containerName){
        List<String> logFiles = getFileList(LOGS_DIRECTORY);

        String latestLogFile = logFiles.stream()
                .filter(logFile -> logFile.startsWith(containerName + "-log"))
                .max((file1, file2) -> { // 최신 파일을 찾기 위해 비교
                    LocalDateTime dateTime1 = extractDateTimeFromLogFile(file1);
                    LocalDateTime dateTime2 = extractDateTimeFromLogFile(file2);
                    return dateTime1.compareTo(dateTime2);
                })
                .orElse(null);

        if(latestLogFile == null){
            return BLANK_STRING;
        }

        String logContent = readFileAsString(latestLogFile);

        return extractLogWithin30Minutes(logContent);
    }

    private LogDataDTO convertToLogData(Map<String, Object> source) {
        String containerName = Optional.ofNullable((String) source.get(LOG_KEY_CONTAINER_NAME))
                .orElse(UNKNOWN_CONTAINER);

        Instant timestamp = parseTimestamp((String) source.get(LOG_KEY_TIMESTAMP));

        String formattedMessage = Optional.ofNullable((String) source.get(LOG_KEY_MESSAGE))
                .map(LogDataParser::formatMessage)
                .orElse(NO_MESSAGE);

        boolean isProcessed = Optional.ofNullable((Boolean) source.get(LOG_KEY_PROCESSED))
                .orElse(false);

        String logLevel = Optional.ofNullable((String) source.get(LOG_KEY_LEVEL))
                .orElse(LOG_LEVEL_UNKNOWN);

        return new LogDataDTO(containerName, timestamp, formattedMessage, isProcessed, logLevel);
    }

    private Instant parseTimestamp(String timestampInStr) {
        return timestampInStr != null ? Instant.parse(timestampInStr) : null;
    }

    private String convertToString(Map<String, Object> source) {
        String rawMessage = (String) source.get(LOG_KEY_MESSAGE);
        return rawMessage != null ? rawMessage : "";
    }

    private SearchResponse<Map> searchFromElasticSearch(String elasticSearchHost) {
        // 오늘 날짜의 인덱스 이름을 생성하여 사용
        String indexName = getTodayIndexName();

        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);

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
            createElasticSearchIndex(indexName, elasticSearchHost);
            return new SearchResponse.Builder<Map>().build();
        } catch (IOException e){
            log.info("<ElasticSearch> "+indexName + "에 대한 검색 실패");
            return null;
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

    private List<Map<String, Object>> refineLogFields(List<Map<String, Object>> logs) {
        return logs.stream()
                .map(log -> {
                    // 맵에서 기존 데이터 추출
                    Map<String, Object> container = (Map<String, Object>) log.get(LOG_KEY_CONTAINER);
                    String containerName = container != null ? (String) container.get(CONTAINER_KEY_NAME) : UNKNOWN_CONTAINER;
                    String message = Optional.ofNullable((String) log.get(LOG_KEY_MESSAGE))
                            .orElse(NO_MESSAGE);

                    // 로그 레벨 추출
                    String logLevel = extractLogLevelFromLog(message);

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

    private void updateToElasticSearch(List<Map<String, Object>> updatedLogMaps, String elasticSearchHost) {
        String indexName = getTodayIndexName();

        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);

            for (Map<String, Object> logMap : updatedLogMaps) {
                String id = (String) logMap.remove(LOG_KEY_ID);

                UpdateRequest<Map<String, Object>, Map<String, Object>> updateRequest = new UpdateRequest.Builder<Map<String, Object>, Map<String, Object>>()
                        .index(indexName)
                        .id(id)
                        .doc(logMap)
                        .build();

                client.update(updateRequest, Map.class);
            }
        } catch (IOException e){
            log.info("<ElasticSearch> "+indexName + "에 대한 업데이트 실패");
        }
    }

    private String extractLogLevelFromLog(String message) {
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

    private void deleteOldLogs(Instant lastCollectedTime, String elasticSearchHost) {
        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);

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
        } catch (IOException e){
            log.info("<ElasticSearch> 데이터 삭제 실패");
        }
    }

    private void saveLogsToFile(List<Map<String, Object>> logs, Long sshId) {
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
            String fileTitle = String.format("logs/%s-log-%s-%d.txt", containerName, timestampForTitle, sshId);
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
                String logContent = convertLogMapToString(logMap, timestamp);

                if (logContent != null) {
                    writer.write(logContent);
                    writer.newLine();
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            log.error("로그 파일에 기록하는 중 오류 발생", e);
        }
    }

    private String convertLogMapToString(Map<String, Object> logMap, String timestamp) {
        String logContent = Optional.ofNullable(logMap.get(LOG_KEY_MESSAGE))
                .map(Object::toString)
                .orElse(null);

        if (logContent == null) {
            return null;
        }

        return String.format(LOG_FORMAT, timestamp, logContent);
    }

    private void createElasticSearchIndex(String indexName, String elasticSearchHost)  {
        try {
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

            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);
            client.indices().create(createIndexRequest);
        } catch (IOException e) {
            log.info("ElasticSearch 인덱스 생성 실패!");
        }
    }

    private String extractLogWithin30Minutes(String logContent) {
        StringBuilder resultLogs = new StringBuilder();

        // 로그 헤더의 날짜 패턴
        String[] logs = logContent.split("(?=\\[\\d{4}-\\d{2}-\\d{2}_\\d{2}:\\d{2}\\])");

        // 30분 전 시간
        LocalDateTime thirtyMinutesAgo = LocalDateTime.now().minus(30, ChronoUnit.MINUTES);

        // 각 로그의 시간을 비교하여 30분 이내의 로그만 추가
        for (String log : logs) {
            if (log.trim().isEmpty()) continue;  // 빈 로그는 건너뜀

            // 로그의 헤더에서 시간을 추출 ([yyyy-MM-dd_HH:mm] 형식)
            String header = log.substring(1, 17);
            LocalDateTime logTime = LocalDateTime.parse(header, formatterWithMinute);

            // 로그 시간이 30분 이내인지 확인
            if (logTime.isAfter(thirtyMinutesAgo)) {
                resultLogs.append(log.trim()).append("\n\n");
            }
        }

        // 결과가 없으면 빈 문자열 반환
        if (resultLogs.length() == 0) {
            return BLANK_STRING;
        }

        return resultLogs.toString().trim();
    }

    private LocalDateTime extractDateTimeFromLogFile(String file) {
        // 파일 이름에서 "log-" 뒤부터 ".txt" 앞까지의 부분 추출
        String dateTimePart = file.substring(file.indexOf("log-") + 4, file.indexOf("-", file.indexOf("_")));
        return LocalDateTime.parse(dateTimePart, formatter);
    }

    private SearchRequest buildSearchRequestQuery(Instant startTime, Instant endTime, String logLevel, String containerName) {
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        // 시간 범위 필터 추가
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

        return new SearchRequest.Builder()
                .index("docker-logs-*")
                .query(q -> q.bool(boolQuery.build()))
                .build();
    }
}
