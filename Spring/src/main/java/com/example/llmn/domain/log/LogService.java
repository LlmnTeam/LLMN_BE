package com.example.llmn.domain.log;

import co.elastic.clients.elasticsearch.core.*;
import com.example.llmn.domain.log.model.LogDataDTO;
import com.example.llmn.integration.elasticsearch.ElasticSearchService;
import com.example.llmn.common.utils.FileUtils;
import com.example.llmn.common.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.example.llmn.common.constants.GlobalConstants.BLANK_STRING;
import static com.example.llmn.common.constants.GlobalConstants.NO_MESSAGE;
import static com.example.llmn.common.utils.DateTimeUtils.*;
import static com.example.llmn.common.utils.FileUtils.createDirectoryIfNotExist;
import static com.example.llmn.common.utils.FileUtils.findTextFiles;
import static com.example.llmn.common.utils.MapUtils.extractBooleanFromMap;
import static com.example.llmn.common.utils.MapUtils.extractStringFromMap;
import static com.example.llmn.domain.log.LogConstants.*;
import static com.example.llmn.integration.elasticsearch.ElasticSearchConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogService {

    private final ElasticSearchService elasticSearchService;

    private static final String CONTAINER_NAME_FIELD = "name";
    private static final String UNKNOWN_CONTAINER = "unknown_container";

    @SuppressWarnings("rawtypes")
    public List<LogDataDTO> searchLog(Instant startTime, Instant endTime, String logLevel, String containerName, String elasticSearchHost) {
        SearchResponse<Map> response = elasticSearchService.searchDocumentsWithFilters(
                ELASTICSEARCH_LOG_INDEX_PATTERN,
                startTime,
                endTime,
                logLevel,
                containerName,
                elasticSearchHost,
                Map.class
        );

        return mapSearchResultsToLogDTOs(response);
    }

    public String findRecentLogs(String containerName) {
        return findLatestLogFile(containerName)
                .map(FileUtils::readFileAsString)
                .map(this::extractRecentLogsFromContent)
                .orElse(BLANK_STRING);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Map<String, Object>> convertElasticsearchResultToLogMap(SearchResponse<Map> searchResponse){
        if (searchResponse.hits() == null || searchResponse.hits().hits() == null)
            return Collections.emptyList();

        return searchResponse
                .hits().hits().stream()
                .map(hit -> {
                    Map<String, Object> logMap = hit.source();
                    if (logMap != null)
                        logMap.put(ES_FIELD_ID, hit.id());

                    return logMap;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public List<Map<String, Object>> standardizeLogFields(List<Map<String, Object>> logDocuments) {
        logDocuments.forEach(logDocument -> {
            String containerName = getContainerNameFromLog(logDocument);
            String message = getLogMessage(logDocument);
            String logLevel = determineLogLevel(message);

            enrichAndStructureLogData(logDocument, logLevel, containerName, message);
        });

        return logDocuments;
    }

    public void persistLogsToFiles(List<Map<String, Object>> logDocuments, Long sshId) {
        createDirectoryIfNotExist(LOGS_DIRECTORY);

        Date now = new Date();
        String timestampForFileName = formatDate(now, LOG_TITLE_FORMAT);
        String timestampForText = formatDate(now, LOG_TEXT_FORMAT);

        Map<String, List<Map<String, Object>>> groupedLogMap = groupLogsByContainerName(logDocuments);
        groupedLogMap.forEach((containerName, containerLogs) -> {
            String fileName = createLogFileName(containerName, timestampForFileName, sshId);
            writeLogsToFile(fileName, containerLogs, timestampForText);
        });
    }

    @SuppressWarnings("unchecked")
    private String getContainerNameFromLog(Map<String, Object> logDocument) {
        Map<String, Object> containerData = (Map<String, Object>) logDocument.get(ES_FIELD_CONTAINER_OBJECT);
        return Optional.ofNullable(containerData)
                .map(map -> (String) map.get(CONTAINER_NAME_FIELD))
                .orElse(UNKNOWN_CONTAINER);
    }

    private String getLogMessage(Map<String, Object> logDocument) {
        return Optional.ofNullable((String) logDocument.get(ES_FIELD_MESSAGE))
                .orElse(NO_MESSAGE);
    }

    private String determineLogLevel(String logContent) {
        return (logContent == null) ? LOG_LEVEL_UNKNOWN :
                Stream.of(LOG_LEVEL_INFO, LOG_LEVEL_ERROR, LOG_LEVEL_WARN)
                        .filter(logContent::contains)
                        .findFirst()
                        .orElse(LOG_LEVEL_INFO);
    }

    private void enrichAndStructureLogData(Map<String, Object> logDocument, String logLevel, String containerName, String message) {
        logDocument.put(ES_FIELD_LEVEL, logLevel);
        logDocument.put(ES_FIELD_CONTAINER_NAME, containerName);
        logDocument.put(ES_FIELD_PROCESSED, true);
        logDocument.put(ES_FIELD_MESSAGE, message);
        logDocument.remove(ES_FIELD_CONTAINER_OBJECT);
    }

    private Map<String, List<Map<String, Object>>> groupLogsByContainerName(List<Map<String, Object>> logDocuments) {
        return logDocuments.stream()
                .collect(Collectors.groupingBy(doc ->
                        (String) doc.getOrDefault(ES_FIELD_CONTAINER_NAME, UNKNOWN_CONTAINER)));
    }

    private String createLogFileName(String containerName, String timestampForTitle, Long sshId) {
        return String.format("logs/%s-log-%s-%d.txt", containerName, timestampForTitle, sshId);
    }

    private void writeLogsToFile(String fileName, List<Map<String, Object>> logEntries, String timestamp) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName, true))) {
            for (Map<String, Object> logEntry : logEntries) {
                String formattedLogEntry = formatLogEntry(logEntry, timestamp);
                writer.write(formattedLogEntry);
                writer.newLine();
                writer.newLine();
            }
        } catch (IOException e) {
            log.error("로그 파일에 기록하는 중 오류 발생", e);
        }
    }

    private String formatLogEntry(Map<String, Object> logEntry, String timestamp) {
        return Optional.ofNullable(logEntry.get(ES_FIELD_MESSAGE))
                .map(Object::toString)
                .map(logMessage -> String.format(LOG_FORMAT, timestamp, logMessage))
                .orElse(EMPTY_LOG_RECORD_MESSAGE);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<LogDataDTO> mapSearchResultsToLogDTOs(SearchResponse<Map> response) {
        if (response.hits() == null || response.hits().hits() == null) {
            return Collections.emptyList();
        }

        return response.hits().hits().stream()
                .map(hit -> createLogDTOFromDocument(hit.source()))
                .toList();
    }

    private LogDataDTO createLogDTOFromDocument(Map<String, Object> logDocument) {
        if (logDocument == null)
            return new LogDataDTO(UNKNOWN_CONTAINER, Instant.now(), NO_MESSAGE, false, LOG_LEVEL_UNKNOWN);

        String containerName = extractStringFromMap(logDocument, ES_FIELD_CONTAINER_NAME, UNKNOWN_CONTAINER);
        Instant timestamp = parseInstant((String) logDocument.get(ES_FIELD_TIMESTAMP));
        String formattedMessage = formatLogMessage(logDocument);
        boolean isProcessed = extractBooleanFromMap(logDocument, ES_FIELD_PROCESSED, false);
        String logLevel = extractStringFromMap(logDocument, ES_FIELD_LEVEL, LOG_LEVEL_UNKNOWN);

        return new LogDataDTO(containerName, timestamp, formattedMessage, isProcessed, logLevel);
    }

    private String formatLogMessage(Map<String, Object> logDocument) {
        return Optional.ofNullable((String) logDocument.get(ES_FIELD_MESSAGE))
                .map(JsonUtils::normalizeJson)
                .orElse(NO_MESSAGE);
    }

    private Optional<String> findLatestLogFile(String containerName) {
        List<String> logFiles = findTextFiles(LOGS_DIRECTORY);
        return logFiles.stream()
                .filter(logFile -> logFile.startsWith(containerName + "-log"))
                .max(this::compareLogFileTimestamps);
    }

    private int compareLogFileTimestamps(String file1, String file2) {
        LocalDateTime fileDateTime1 = extractDateTimeFromLogFileName(file1);
        LocalDateTime fileDateTime2 = extractDateTimeFromLogFileName(file2);
        return fileDateTime1.compareTo(fileDateTime2);
    }

    private LocalDateTime extractDateTimeFromLogFileName(String file) {
        // 파일 이름에서 "log-" 뒤부터 ".txt" 앞까지의 부분 추출
        String dateTimePart = file.substring(file.indexOf("log-") + 4, file.indexOf("-", file.indexOf("_")));
        return LocalDateTime.parse(dateTimePart, LOG_FILE_FORMATTER);
    }

    private String extractRecentLogsFromContent(String fileContent) {
        String[] logs = fileContent.split(LOG_FILE_CONTENT_REGEX);
        String recentLogs = Arrays.stream(logs)
                .map(String::trim)
                .filter(log -> !log.isEmpty())
                .filter(log -> isLogWithinTimeFrame(log, getThirtyMinutesAgoTime()))
                .collect(Collectors.joining("\n\n"));

        return recentLogs.isEmpty() ? BLANK_STRING : recentLogs;
    }

    private boolean isLogWithinTimeFrame(String log, LocalDateTime cutoffTime) {
        String logTimestamp = extractTimestampFromLog(log);
        LocalDateTime logTime = LocalDateTime.parse(logTimestamp, LOG_TEXT_FORMATTER);
        return logTime.isAfter(cutoffTime);
    }

    private String extractTimestampFromLog(String log) {
        return log.substring(1, 17);
    }
}