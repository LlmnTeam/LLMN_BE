package com.example.llmn.service;

import com.example.llmn.controller.DTO.SearchResponse;
import com.example.llmn.domain.Project;
import com.example.llmn.domain.Summary;
import com.example.llmn.repository.ProjectRepository;
import com.example.llmn.repository.SummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.example.llmn.core.utils.DateTimeUtils.*;
import static com.example.llmn.core.utils.FileUtils.LOGS_DIRECTORY;
import static com.example.llmn.core.utils.FileUtils.getTextFiles;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {

    private final ProjectRepository projectRepository;
    private final SummaryRepository summaryRepository;
    private static final String LOG_FILE_URI_TEMPLATE = "/project/%d/%s";

    @Transactional(readOnly = true)
    public SearchResponse.SearchDTO search(String keyword, LocalDateTime startDate, LocalDateTime endDate, Long userId) {
        List<Project> projects = projectRepository.findByUserId(userId);
        Map<String, Long> containerNameToProjectIdMap = createContainerNameToProjectIdMap(projects);

        List<String> logFiles = getTextFiles(LOGS_DIRECTORY);
        List<SearchResponse.LogFileDTO> searchedLogDTOS = searchLogFiles(logFiles, keyword.toLowerCase(), startDate, endDate, containerNameToProjectIdMap);

        List<SearchResponse.InsightDTO> searchedInsightDTOS = searchInsights(projects, keyword.toLowerCase(), startDate, endDate);

        return new SearchResponse.SearchDTO(searchedLogDTOS, searchedInsightDTOS);
    }

    private List<SearchResponse.LogFileDTO> searchLogFiles(List<String> logFiles, String keyword, LocalDateTime startDate, LocalDateTime endDate, Map<String, Long> containerNameMap) {
        return logFiles.stream()
                .filter(fileName -> isKeywordContain(fileName, keyword))
                .filter(fileName -> isWithinDateRange(parseDateTimeFromLogFile(fileName), startDate, endDate))
                .map(fileName -> createLogFileDTO(fileName, containerNameMap))
                .toList();
    }

    private Map<String, Long> createContainerNameToProjectIdMap(List<Project> userProjects) {
        return userProjects.stream()
                .collect(Collectors.toMap(Project::getContainerName, Project::getId));
    }

    private boolean isKeywordContain(String value, String keyword) {
        return value.toLowerCase().contains(keyword);
    }

    private SearchResponse.LogFileDTO createLogFileDTO(String fileName, Map<String, Long> containerNameToProjectIdMap) {
        String containerName = extractContainerName(fileName);
        Long projectId = containerNameToProjectIdMap.get(containerName);
        String redirectURI = buildLogViewRedirectURI(projectId, fileName);

        return new SearchResponse.LogFileDTO(fileName, redirectURI);
    }

    private List<SearchResponse.InsightDTO> searchInsights(List<Project> projects, String keyword, LocalDateTime startDate, LocalDateTime endDate) {
        List<Project> relatedProjects = findRelatedProjects(projects, keyword);
        List<Summary> summaries = summaryRepository.findByProjectsAndDateRange(relatedProjects, startDate, endDate);

        return createInsightDTOs(summaries);
    }

    private List<Project> findRelatedProjects(List<Project> projects, String keyword) {
        return projects.stream()
                .filter(project -> project.isProjectRelatedToKeyword(keyword))
                .distinct()
                .toList();
    }

    private List<SearchResponse.InsightDTO> createInsightDTOs(List<Summary> summaries) {
        return summaries.stream()
                .map(summary -> new SearchResponse.InsightDTO(
                        summary.getProject().getProjectName(),
                        formatLocalDateTime(summary.getCreatedDate()),
                        summary.getSummaryType(),
                        summary.getContent()))
                .toList();
    }

    // 로그 파일명 형식인 '컨테이너명-log-날짜.txt'에서 컨테이너명 추출하기
    private String extractContainerName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }

        String[] fileNameParts = fileName.split("-");
        return fileNameParts.length > 0 ? fileNameParts[0] : "";
    }

    private String buildLogViewRedirectURI(Long projectId, String fileName) {
        return String.format(LOG_FILE_URI_TEMPLATE, projectId, fileName);
    }
}
