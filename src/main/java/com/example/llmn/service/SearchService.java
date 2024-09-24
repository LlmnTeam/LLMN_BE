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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {

    private final LogService logService;
    private final ProjectRepository projectRepository;
    private final SummaryRepository summaryRepository;
    private static final String LOG_FILE_URL_TEMPLATE = "/project/%d/%s";

    @Transactional(readOnly = true)
    public SearchResponse.SearchDTO search(String keyword, LocalDateTime startDate, LocalDateTime endDate, Long userId){
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

        // 검색 키워드와 연관된 프로젝트 검색
        List<Project> searchedProjects = projects.stream()
                .filter(project -> project.getProjectName().toLowerCase().contains(lowerCaseKeyword)
                        || project.getContainerName().toLowerCase().contains(lowerCaseKeyword)
                        || lowerCaseKeyword.contains(project.getProjectName().toLowerCase())
                        || lowerCaseKeyword.contains(project.getContainerName().toLowerCase()))
                .toList();

        // 검색된 프로젝트의 인사이트 목록 조회
        for(Project project : searchedProjects) {
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

        // 파일 이름에서 날짜와 시간을 추출하는 정규식
        String regex = ".*-log-(\\d{4}-\\d{2}-\\d{2})_(\\d{2})\\.txt";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(logFileName);

        if (matcher.matches()) {
            String datePart = matcher.group(1); // yyyy-MM-dd
            String hourPart = matcher.group(2); // HH

            // '2024-09-13 20' 형태로 날짜와 시간을 합침
            String dateTimeString = datePart + " " + hourPart;
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH");

            return LocalDateTime.parse(dateTimeString, formatter);
        } else {
            log.info("로그 파일 목록 중 잘못된 형식의 파일이 존재.");
            return null;
        }
    }

    private String buildLogfileRedirectURL(Long projectId, String fileName) {
        return String.format(LOG_FILE_URL_TEMPLATE, projectId, fileName);
    }

    private String formatLocalDateTime(LocalDateTime localDateTime) {
        if(localDateTime == null){
            return null;
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        return localDateTime.format(formatter);
    }
}
