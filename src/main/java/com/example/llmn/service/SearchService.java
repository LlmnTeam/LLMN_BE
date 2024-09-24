package com.example.llmn.service;

import com.example.llmn.controller.DTO.SearchResponse;
import com.example.llmn.domain.Project;
import com.example.llmn.domain.Summary;
import com.example.llmn.repository.ProjectRepository;
import com.example.llmn.repository.SummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SearchService {

    private final LogService logService;
    private final ProjectRepository projectRepository;
    private final SummaryRepository summaryRepository;

    @Transactional(readOnly = true)
    public SearchResponse.SearchDTO search(Long userId, LocalDateTime startDate, LocalDateTime endDate, String keyword){
        // 대소문자 구분없이 검색하기 위해 소문자로 변환
        String lowerCaseKeyword = keyword.toLowerCase();

        // <containerName, projectId> 형태의 맵 생성
        List<Project> projects = projectRepository.findByUserId(userId);
        Map<String, Long> projectMap = projects.stream()
                .collect(Collectors.toMap(Project::getContainerName, Project::getId));

        // 1. 로그 파일 검색
        List<String> logFiles = logService.findLogFileList();

        List<SearchResponse.LogFileDTO> logFileDTOS = logFiles.stream()
                .filter(logFileName -> logFileName.toLowerCase().contains(lowerCaseKeyword)) // 키워드 필터링
                .filter(logFileName -> { // 시간 필터링 (시작일 ~ 종료일)
                    LocalDateTime logDateTime = extractDateTime(logFileName);
                    return logDateTime != null && (logDateTime.isEqual(startDate) || logDateTime.isAfter(startDate)) // 시작일자 이후
                            && (logDateTime.isEqual(endDate) || logDateTime.isBefore(endDate)); // 종료일자 이전
                })
                .map(logFileName -> {
                    String containerName = extractContainerName(logFileName);
                    Long projectId = projectMap.get(containerName);
                    String redirectURL = buildLogfileRedirectURL(projectId,logFileName);
                    return new SearchResponse.LogFileDTO(logFileName, redirectURL);
                })
                .toList();

        // 2. 인사이트 기록 검색
        List<Summary> summaries = new ArrayList<>();

        for(Project project : projects) {
            List<Summary> foundSummaries = summaryRepository.findByProjectAndDateRange(project, startDate, endDate);
            summaries.addAll(foundSummaries);
        }

        List<SearchResponse.InsightDTO> insightDTOS = summaries.stream()
                .map(summary -> new SearchResponse.InsightDTO(
                        summary.getProject().getProjectName(),
                        formatLocalDateTime(summary.getCreatedDate()),
                        summary.getSummaryType(),
                        summary.getContent()))
                .toList();

        return new SearchResponse.SearchDTO(logFileDTOS, insightDTOS);
    }

    // 로그 파일명 형식인 '프로젝트명-log-날짜.txt'에서 프로젝트명 추출하기
    private String extractContainerName(String logFileName) {
        if (logFileName == null || logFileName.isEmpty()) {
            return "";
        }

        // 파일 이름을 '-'로 분리
        String[] parts = logFileName.split("-");

        return parts.length > 0 ? parts[0] : "";
    }

    public LocalDateTime extractDateTime(String logFileName) {
        if (logFileName == null || logFileName.isEmpty()) {
            return null;
        }

        // 파일 이름을 '-'로 분리
        String[] parts = logFileName.split("-");
        if (parts.length < 3) {
            return null;
        }

        // 날짜 부분은 세 번째 요소, 시간은 언더스코어(_)로 구분
        String[] dateTimePart = parts[2].split("_");
        if (dateTimePart.length < 2) {
            return null;
        }

        // '2024-09-22 14'와 같은 형태로 날짜와 시간을 하나로 합침
        String dateTimeString = dateTimePart[0] + " " + dateTimePart[1];

        // LocalDateTime으로의 파싱을 위한 포맷터
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH");

        return LocalDateTime.parse(dateTimeString, formatter);
    }

    private String buildLogfileRedirectURL(Long projectId, String fileName){
        String redirectURL = "/project/" + projectId + "/" + fileName;
        return redirectURL;
    }

    private String formatLocalDateTime(LocalDateTime localDateTime) {
        if(localDateTime == null){
            return null;
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        return localDateTime.format(formatter);
    }
}
