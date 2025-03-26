package com.example.llmn.domain.log;

import co.elastic.clients.elasticsearch.core.*;
import com.example.llmn.domain.log.model.LogDataDTO;
import com.example.llmn.integration.elasticsearch.ElasticSearchService;
import com.example.llmn.common.utils.FileUtils;
import com.example.llmn.common.utils.JsonUtils;
import com.example.llmn.domain.remote.SshInfo;
import com.example.llmn.domain.remote.SshInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final SshInfoRepository sshInfoRepository;

    private static final String CONTAINER_KEY_NAME = "name";
    private static final String UNKNOWN_CONTAINER = "unknown_container";
    private static final String LOG_FORMAT = "[%s]\n%s";

    @Scheduled(fixedRate = 60000)
    @SuppressWarnings("rawtypes")
    public void processAndUpdateLogs() {
        List<SshInfo> sshInfos = sshInfoRepository.findAll();

        for(SshInfo sshInfo : sshInfos) {
            SearchResponse<Map> searchResponse = elasticSearchService.searchUnprocessedDocuments(
                    getLogIndex(),
                    sshInfo.getRemoteHost(),
                    Map.class,
                    MAX_LOG_SIZE
            );

            List<Map<String, Object>> logMaps = convertResponseToMap(searchResponse);
            List<Map<String, Object>> refinedLogMaps = refineLogFields(logMaps);

            elasticSearchService.updateDocuments(getLogIndex(), refinedLogMaps, sshInfo.getRemoteHost());
            saveLogMapsToFile(refinedLogMaps, sshInfo.getId());
        }
    }

    public void deleteLogsBefore(Instant cutoffTime, String elasticSearchHost) {
        elasticSearchService.deleteDocumentsBefore(LOG_INDEX, cutoffTime, elasticSearchHost);
    }

    @SuppressWarnings("rawtypes")
    public List<LogDataDTO> searchLog(Instant startTime, Instant endTime, String logLevel, String containerName, String elasticSearchHost) {
        SearchResponse<Map> response = elasticSearchService.searchWithFilters(
                LOG_INDEX,
                startTime,
                endTime,
                logLevel,
                containerName,
                elasticSearchHost,
                Map.class
        );

        return convertSearchHitsToDTOs(response);
    }

    public String findRecentLogs(String containerName) {
        return findLatestLogFile(containerName)
                .map(FileUtils::readFileAsString)
                .map(this::extractRecentLogFrom)
                .orElse(BLANK_STRING);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map<String, Object>> convertResponseToMap(SearchResponse<Map> searchResponse){
        if (searchResponse.hits() == null || searchResponse.hits().hits() == null) {
            return Collections.emptyList();
        }

        return searchResponse
                .hits().hits().stream()
                .map(hit -> {
                    Map<String, Object> logMap = hit.source();
                    if (logMap != null) {
                        logMap.put(ES_FIELD_ID, hit.id());
                    }
                    return logMap;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private List<Map<String, Object>> refineLogFields(List<Map<String, Object>> logMaps) {
        logMaps.forEach(logMap -> {
            String containerName = extractContainerNameFromLogMap(logMap);
            String message = extractMessageFromLogMap(logMap);
            String logLevel = extractLogLevelFromLog(message);

            updateLogFields(logMap, logLevel, containerName, message);
        });

        return logMaps;
    }

    @SuppressWarnings("unchecked")
    private String extractContainerNameFromLogMap(Map<String, Object> logMap) {
        Map<String, Object> container = (Map<String, Object>) logMap.get(ES_FIELD_CONTAINER_OBJECT);
        return Optional.ofNullable(container)
                .map(map -> (String) map.get(CONTAINER_KEY_NAME))
                .orElse(UNKNOWN_CONTAINER);
    }

    private String extractMessageFromLogMap(Map<String, Object> logMap) {
        return Optional.ofNullable((String) logMap.get(ES_FIELD_MESSAGE))
                .orElse(NO_MESSAGE);
    }

    private String extractLogLevelFromLog(String logContent) {
        return (logContent == null) ? LOG_LEVEL_UNKNOWN :
                Stream.of(LOG_LEVEL_INFO, LOG_LEVEL_ERROR, LOG_LEVEL_WARN)
                        .filter(logContent::contains)
                        .findFirst()
                        .orElse(LOG_LEVEL_INFO);
    }

    private void updateLogFields(Map<String, Object> logMap, String logLevel, String containerName, String message) {
        logMap.put(ES_FIELD_LEVEL, logLevel);
        logMap.put(ES_FIELD_CONTAINER_NAME, containerName);
        logMap.put(ES_FIELD_PROCESSED, true);
        logMap.put(ES_FIELD_MESSAGE, message);
        logMap.remove(ES_FIELD_CONTAINER_OBJECT);
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
                .collect(Collectors.groupingBy(log -> (String) log.getOrDefault(ES_FIELD_CONTAINER_NAME, UNKNOWN_CONTAINER)));
    }

    private String buildLogFileName(String containerName, String timestampForTitle, Long sshId) {
        return String.format("logs/%s-log-%s-%d.txt", containerName, timestampForTitle, sshId);
    }

    private void writeLogsToFile(String fileName, List<Map<String, Object>> logMaps, String timestamp) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName, true))) {
            for (Map<String, Object> logMap : logMaps) {
                String logContent = extractLogContentInMap(logMap, timestamp);
                writer.write(logContent);
                writer.newLine();
                writer.newLine();
            }
        } catch (IOException e) {
            log.error("로그 파일에 기록하는 중 오류 발생", e);
        }
    }

    private String extractLogContentInMap(Map<String, Object> logMap, String timestamp) {
        return Optional.ofNullable(logMap.get(ES_FIELD_MESSAGE))
                .map(Object::toString)
                .map(logMessage -> String.format(LOG_FORMAT, timestamp, logMessage))
                .orElse(NO_LOG_RECORD);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<LogDataDTO> convertSearchHitsToDTOs(SearchResponse<Map> response) {
        if (response.hits() == null || response.hits().hits() == null) {
            return Collections.emptyList();
        }

        return response.hits().hits().stream()
                .map(hit -> convertResponseMapToDTO(hit.source()))
                .toList();
    }

    private LogDataDTO convertResponseMapToDTO(Map<String, Object> responseMap) {
        if (responseMap == null) {
            return new LogDataDTO(UNKNOWN_CONTAINER, Instant.now(), NO_MESSAGE, false, LOG_LEVEL_UNKNOWN);
        }

        String containerName = extractStringFromMap(responseMap, ES_FIELD_CONTAINER_NAME, UNKNOWN_CONTAINER);
        Instant timestamp = parseInstant((String) responseMap.get(ES_FIELD_TIMESTAMP));
        String formattedMessage = formatLogMessage(responseMap);
        boolean isProcessed = extractBooleanFromMap(responseMap, ES_FIELD_PROCESSED, false);
        String logLevel = extractStringFromMap(responseMap, ES_FIELD_LEVEL, LOG_LEVEL_UNKNOWN);

        return new LogDataDTO(containerName, timestamp, formattedMessage, isProcessed, logLevel);
    }

    private String formatLogMessage(Map<String, Object> responseMap) {
        return Optional.ofNullable((String) responseMap.get(ES_FIELD_MESSAGE))
                .map(JsonUtils::normalizeJson)
                .orElse(NO_MESSAGE);
    }

    private Optional<String> findLatestLogFile(String containerName) {
        List<String> logFiles = findTextFiles(LOGS_DIRECTORY);

        return logFiles.stream()
                .filter(logFile -> logFile.startsWith(containerName + "-log"))
                .max(this::compareLogFileDates);
    }

    private int compareLogFileDates(String file1, String file2) {
        LocalDateTime fileDateTime1 = extractDateTimeFromLogFile(file1);
        LocalDateTime fileDateTime2 = extractDateTimeFromLogFile(file2);
        return fileDateTime1.compareTo(fileDateTime2);
    }

    private LocalDateTime extractDateTimeFromLogFile(String file) {
        // 파일 이름에서 "log-" 뒤부터 ".txt" 앞까지의 부분 추출
        String dateTimePart = file.substring(file.indexOf("log-") + 4, file.indexOf("-", file.indexOf("_")));
        return LocalDateTime.parse(dateTimePart, LOG_FILE_FORMATTER);
    }

    private String extractRecentLogFrom(String fileContent) {
        String[] logs = fileContent.split(LOG_FILE_CONTENT_REGEX);

        String recentLogs = Arrays.stream(logs)
                .map(String::trim)
                .filter(log -> !log.isEmpty())
                .filter(log -> isLogWithinLast30Minutes(log, getThirtyMinutesAgoTime()))
                .collect(Collectors.joining("\n\n"));

        return recentLogs.isEmpty() ? BLANK_STRING : recentLogs;
    }

    private boolean isLogWithinLast30Minutes(String log, LocalDateTime cutoffTime) {
        String header = extractHeaderFromLog(log);
        LocalDateTime logTime = LocalDateTime.parse(header, LOG_TEXT_FORMATTER);
        return logTime.isAfter(cutoffTime);
    }

    private String extractHeaderFromLog(String log) {
        return log.substring(1, 17);
    }

    private String getLogIndex() {
        // 오늘 날짜를 기반으로 인덱스 이름 생성
        return LOG_INDEX_PREFIX + getTodayDateInString();
    }
}