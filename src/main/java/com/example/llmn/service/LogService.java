package com.example.llmn.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.json.JsonData;
import com.example.llmn.controller.DTO.LogDataDTO;
import com.example.llmn.core.config.ElasticsearchConfig;
import com.example.llmn.core.utils.FileUtils;
import com.example.llmn.core.utils.JsonUtils;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.repository.SshInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.example.llmn.core.utils.MapUtils.extractBooleanFromMap;
import static com.example.llmn.core.utils.MapUtils.extractStringFromMap;
import static com.example.llmn.core.utils.DateTimeUtils.*;
import static com.example.llmn.core.utils.FileUtils.*;

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
    private static final int MAX_LOG_SIZE = 1000;
    public static final String LOG_INDEX = "docker-logs-*";

    @Scheduled(fixedRate = 60000)
    public void processAndUpdateLogs() {
        List<SshInfo> sshInfos = sshInfoRepository.findAll();

        for(SshInfo sshInfo : sshInfos) {
            SearchResponse<Map> searchResponse = searchLogsInElasticSearch(sshInfo.getRemoteHost());

            List<Map<String, Object>> logMaps = convertResponseToMap(searchResponse);
            List<Map<String, Object>> refinedLogMaps = refineLogFields(logMaps);

            updateLogToElasticSearch(refinedLogMaps, sshInfo.getRemoteHost());
            saveLogMapsToFile(refinedLogMaps, sshInfo.getId());
        }
    }

    public List<LogDataDTO> searchLog(Instant startTime, Instant endTime, String logLevel, String containerName, String elasticSearchHost) {
        try {
            SearchRequest searchRequest = buildSearchRequest(startTime, endTime, logLevel, containerName);
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);
            SearchResponse<Map> response = client.search(searchRequest, Map.class);

            return convertSearchHitsToDTOs(response);
        } catch (IOException e) {
            log.error("<ElasticSearch> {} 어플리케이션에 대해 검색 실패", containerName);
            return new ArrayList<>();
        }
    }

    public void deleteLogsBefore(Instant cutoffTime, String elasticSearchHost) {
        try {
            DeleteByQueryRequest deleteRequest = buildDeleteRequest(cutoffTime);
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);
            client.deleteByQuery(deleteRequest);
        } catch (IOException e){
            log.info("<ElasticSearch> 데이터 삭제 실패");
        }
    }

    public String findRecentLogs(String containerName) {
        return findLatestLogFile(containerName)
                .map(FileUtils::readFileAsString)
                .map(this::extractRecentLogFrom)
                .orElse(BLANK_STRING);
    }

    private SearchResponse<Map> searchLogsInElasticSearch(String elasticSearchHost) {
        String index = createLogIndex();

        try {
            SearchRequest searchRequest = buildSearchRequest(index);
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);

            return client.search(searchRequest, Map.class);
        } catch (ElasticsearchException e) {
            createElasticSearchIndex(index, elasticSearchHost);
            return new SearchResponse.Builder<Map>().build();
        } catch (IOException e){
            log.error("<ElasticSearch> {}에 대한 검색 실패", index);
            return new SearchResponse.Builder<Map>().build();
        }
    }

    private List<Map<String, Object>> refineLogFields(List<Map<String, Object>> logMaps) {
        return logMaps.stream()
                .map(logMap -> {
                    String containerName = extractContainerNameFromLogMap(logMap);
                    String message = extractMessageFromLogMap(logMap);
                    String logLevel = extractLogLevelFromLog(message);

                    updateLogFields(logMap, logLevel, containerName, message);
                    return logMap;
                })
                .toList();
    }

    private void updateLogFields(Map<String, Object> log, String logLevel, String containerName, String message) {
        log.put(LOG_KEY_LEVEL, logLevel);
        log.put(LOG_KEY_CONTAINER_NAME, containerName);
        log.put(LOG_KEY_PROCESSED, true);
        log.put(LOG_KEY_MESSAGE, message);
        log.remove(LOG_KEY_CONTAINER);
    }

    private void updateLogToElasticSearch(List<Map<String, Object>> updatedLogMaps, String elasticSearchHost) {
        String logIndex = createLogIndex();

        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);

            for (Map<String, Object> logMap : updatedLogMaps) {
                String id = (String) logMap.remove(LOG_KEY_ID);
                UpdateRequest<Map<String, Object>, Map<String, Object>> updateRequest = buildUpdateRequest(logIndex, logMap, id);
                client.update(updateRequest, Map.class);
            }
        } catch (IOException e){
            log.error("<ElasticSearch> {}에 대한 업데이트 실패", logIndex);
        }
    }

    private List<LogDataDTO> convertSearchHitsToDTOs(SearchResponse<Map> response) {
        return response.hits().hits().stream()
                .map(hit -> convertResponseMapToDTO(hit.source()))
                .toList();
    }

    private LogDataDTO convertResponseMapToDTO(Map<String, Object> responseMap) {
        String containerName = extractStringFromMap(responseMap, LOG_KEY_CONTAINER_NAME, UNKNOWN_CONTAINER);
        Instant timestamp = parseInstant((String) responseMap.get(LOG_KEY_TIMESTAMP));
        String formattedMessage = formatLogMessage(responseMap);
        boolean isProcessed = extractBooleanFromMap(responseMap, LOG_KEY_PROCESSED, false);
        String logLevel = extractStringFromMap(responseMap, LOG_KEY_LEVEL, LOG_LEVEL_UNKNOWN);

        return new LogDataDTO(containerName, timestamp, formattedMessage, isProcessed, logLevel);
    }


    private String formatLogMessage(Map<String, Object> responseMap) {
        return Optional.ofNullable((String) responseMap.get(LOG_KEY_MESSAGE))
                .map(JsonUtils::normalizeJson)
                .orElse(NO_MESSAGE);
    }

    // 나중에 searchLog() 반환 타입을 List<LogDataDTO>이 아닌 String으로 받고 싶을 때 사용
    private String convertSearchHitsToString(SearchResponse<Map> response) {
        List<String> logContents = response.hits().hits().stream()
                .map(hit -> convertResponseMapToString(hit.source()))
                .toList();

        return String.join("\n", logContents);
    }

    private String convertResponseMapToString(Map<String, Object> responseMap) {
        String logContent = (String) responseMap.get(LOG_KEY_MESSAGE);
        return logContent != null ? logContent : "";
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

    private List<Map<String, Object>> convertResponseToMap(SearchResponse<Map> searchResponse){
        return searchResponse
                .hits().hits().stream()
                .map(hit -> {
                    Map<String, Object> logMap = hit.source();
                    logMap.put(LOG_KEY_ID, hit.id());
                    return logMap;
                })
                .toList();
    }

    private String extractLogLevelFromLog(String logContent) {
        return (logContent == null) ? LOG_LEVEL_UNKNOWN :
                Stream.of(LOG_LEVEL_INFO, LOG_LEVEL_ERROR, LOG_LEVEL_WARN)
                        .filter(logContent::contains)
                        .findFirst()
                        .orElse(LOG_LEVEL_INFO);
    }

    private Optional<String> findLatestLogFile(String containerName) {
        List<String> logFiles = findTextFiles(LOGS_DIRECTORY);

        return logFiles.stream()
                .filter(logFile -> logFile.startsWith(containerName + "-log"))
                .max(this::compareLogFileDates);
    }

    private String extractRecentLogFrom(String fileContent) {
        LocalDateTime cutoffTime = getThirtyMinutesAgoTime();

        String[] logs = fileContent.split("(?=\\[\\d{4}-\\d{2}-\\d{2}_\\d{2}:\\d{2}\\])");

        String recentLogs = Arrays.stream(logs)
                .map(String::trim)
                .filter(log -> !log.isEmpty())
                .filter(log -> isLogWithinLast30Minutes(log, cutoffTime))
                .collect(Collectors.joining("\n\n"));

        return recentLogs.isEmpty() ? BLANK_STRING : recentLogs;
    }

    private String extractContainerNameFromLogMap(Map<String, Object> log) {
        Map<String, Object> container = (Map<String, Object>) log.get(LOG_KEY_CONTAINER);
        return Optional.ofNullable(container)
                .map(c -> (String) c.get(CONTAINER_KEY_NAME))
                .orElse(UNKNOWN_CONTAINER);
    }

    private String extractMessageFromLogMap(Map<String, Object> log) {
        return Optional.ofNullable((String) log.get(LOG_KEY_MESSAGE))
                .orElse(NO_MESSAGE);
    }

    private String createLogIndex() {
        // 오늘 날짜를 기반으로 인덱스 이름 생성
        return "docker-logs-" + getTodayDateInString();
    }

    private void saveLogMapsToFile(List<Map<String, Object>> logMaps, Long sshId) {
        createDirectoryIfNotExist(LOGS_DIRECTORY);

        Date now = new Date();
        String timestampForFileName = formatDate(now, LOG_TITLE_FORMAT);
        String timestampForText = formatDate(now, LOG_TEXT_FORMAT);

        Map<String, List<Map<String, Object>>> groupedLogMap = groupByContainerName(logMaps);
        groupedLogMap.forEach((containerName, maps) -> {
            String fileName = buildLogFileName(containerName, timestampForFileName, sshId);
            writeLogsToFile(fileName, maps, timestampForText);
        });
    }

    private Map<String, List<Map<String, Object>>> groupByContainerName(List<Map<String, Object>> logMaps) {
        return logMaps.stream()
                .collect(Collectors.groupingBy(log -> (String) log.getOrDefault(LOG_KEY_CONTAINER_NAME, UNKNOWN_CONTAINER)));
    }

    private String buildLogFileName(String containerName, String timestampForTitle, Long sshId) {
        return String.format("logs/%s-log-%s-%d.txt", containerName, timestampForTitle, sshId);
    }

    private void writeLogsToFile(String fileName, List<Map<String, Object>> logMaps, String timestamp) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName, true))) {
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

    private SearchRequest buildSearchRequest(Instant startTime, Instant endTime, String logLevel, String containerName) {
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        addTimeRangeFilter(boolQuery, startTime, endTime);
        if (logLevel != null) addTermFilter(boolQuery, LOG_KEY_LEVEL, logLevel);
        if (containerName != null) addTermFilter(boolQuery, LOG_KEY_CONTAINER_NAME, containerName);

        return new SearchRequest.Builder()
                .index(LOG_INDEX)
                .query(q -> q.bool(boolQuery.build()))
                .build();
    }

    private void addTimeRangeFilter(BoolQuery.Builder boolQuery, Instant startTime, Instant endTime) {
        boolQuery.must(m -> m.range(r -> r
                .field(LOG_KEY_TIMESTAMP)
                .gte(JsonData.of(startTime.toString()))
                .lte(JsonData.of(endTime.toString()))
        ));
    }

    private void addTermFilter(BoolQuery.Builder boolQuery, String field, String value) {
        boolQuery.filter(f -> f.term(t -> t
                .field(field)
                .value(value)
        ));
    }

    private SearchRequest buildSearchRequest(String index) {
        return new SearchRequest.Builder()
                .index(index)
                .query(q -> q.bool(b -> b
                        .should(s -> s.term(t -> t.field(LOG_KEY_PROCESSED).value(false)))  // is_processed가 false인 로그
                        .should(s -> s.bool(bs -> bs.mustNot(mn -> mn.exists(e -> e.field(LOG_KEY_PROCESSED)))))  // is_processed 필드가 없는 로그
                ))
                .size(MAX_LOG_SIZE)
                .build();
    }

    private DeleteByQueryRequest buildDeleteRequest(Instant cutoffTime) {
        return DeleteByQueryRequest.of(d -> d
                .index(LOG_INDEX)
                .query(q -> q.range(r -> r
                        .field(LOG_KEY_TIMESTAMP)
                        .lte(JsonData.of(cutoffTime.toString()))
                ))
        );
    }

    private UpdateRequest<Map<String, Object>, Map<String, Object>> buildUpdateRequest(String index, Map<String, Object> logMap, String id) {
        return new UpdateRequest.Builder<Map<String, Object>, Map<String, Object>>()
                .index(index)
                .id(id)
                .doc(logMap)
                .build();
    }

    private int compareLogFileDates(String file1, String file2) {
        LocalDateTime fileDateTime1 = extractDateTimeFromLogFile(file1);
        LocalDateTime fileDateTime2 = extractDateTimeFromLogFile(file2);
        return fileDateTime1.compareTo(fileDateTime2);
    }

    private LocalDateTime extractDateTimeFromLogFile(String file) {
        // 파일 이름에서 "log-" 뒤부터 ".txt" 앞까지의 부분 추출
        String dateTimePart = file.substring(file.indexOf("log-") + 4, file.indexOf("-", file.indexOf("_")));
        return LocalDateTime.parse(dateTimePart, formatter);
    }

    private boolean isLogWithinLast30Minutes(String log, LocalDateTime cutoffTime) {
        String header = extractHeaderFromLog(log);
        LocalDateTime logTime = LocalDateTime.parse(header, formatterWithMinute);
        return logTime.isAfter(cutoffTime);
    }

    private String extractHeaderFromLog(String log) {
        return log.substring(1, 17);
    }
}
