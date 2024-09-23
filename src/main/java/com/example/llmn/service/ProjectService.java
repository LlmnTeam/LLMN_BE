package com.example.llmn.service;

import com.example.llmn.controller.DTO.ProjectRequest;
import com.example.llmn.controller.DTO.ProjectResponse;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.domain.*;
import com.example.llmn.repository.ProjectRepository;
import com.example.llmn.repository.SummaryRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProjectService {

    private final DockerService dockerService;
    private final LogService logService;
    private final ProjectRepository projectRepository;
    private final SummaryRepository summaryRepository;
    private final EntityManager entityManager;

    @Transactional
    public ProjectResponse.CreateProjectDTO createProject(ProjectRequest.CreateProjectDTO requestDTO, Long userId){
        // 컨테이너 이름이 들어오지 않으면 NOT_CONNECTED로 처리
        ContainerStatus containerStatus = requestDTO.containerName() != null
                ? ContainerStatus.NOT_WORKING
                : ContainerStatus.NOT_CONNECTED;

        // NOT_CONNECTED면 긴급으로 표시
        boolean isUrgent = (containerStatus == ContainerStatus.NOT_CONNECTED);

        User user = entityManager.getReference(User.class, userId);
        Project project = Project.builder()
                .user(user)
                .projectName(requestDTO.projectName())
                .containerName(requestDTO.containerName())
                .description(requestDTO.description())
                .containerStatus(containerStatus)
                .isUrgent(isUrgent)
                .build();

        projectRepository.save(project);

        return new ProjectResponse.CreateProjectDTO(project.getId());
    }

    // 수정 시 사용할 API
    @Transactional(readOnly = true)
    public ProjectResponse.FindProjectInfoByIdDTO findProjectInfoById(Long projectId){
        // 존재하지 않으면 에러
        Project project = projectRepository.findById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        return new ProjectResponse.FindProjectInfoByIdDTO(
                project.getProjectName(),
                project.getContainerName(),
                project.getDescription());
    }

    @Transactional
    public void updateProject(ProjectRequest.UpdateProjectDTO requestDTO, Long projectId, Long userId){
        // 존재하지 않으면 에러
        Project project = projectRepository.findById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // 권한 체크
        if(project.getUser().getId().equals(userId)){
            throw new CustomException(ExceptionCode.USER_FORBIDDEN);
        }

        ContainerStatus containerStatus = requestDTO.containerName() != null
                ? ContainerStatus.NOT_WORKING
                : project.getContainerStatus();

        project.updateProject(requestDTO.projectName(), requestDTO.containerName(), requestDTO.description(), containerStatus);
    }

    @Transactional
    public ProjectResponse.FindProjectListDTO findProjectList(Long userId, boolean isUsingCache) throws Exception {
        List<Project> projects = projectRepository.findByUserId(userId);

        // 컨테이너 리소스 조회
        Map<String, Map<String, String>> containersResourceUsageMap = dockerService.findContainersResourceUsage(userId, isUsingCache);

        // 실행중인 컨테이너 목록 (Map의 키가 컨테이너 이름이니 이를 리스트로 변환)
        List<String> runningContainerNames = new ArrayList<>(containersResourceUsageMap.keySet());

        List<ProjectResponse.ProjectDTO> projectDTOS = projects.stream()
                .map(project -> {
                    ContainerStatus containerStatus = project.getContainerStatus();

                    // 연결된 상태라면 도커 상태 체크
                    if (containerStatus != ContainerStatus.NOT_CONNECTED) {
                        containerStatus = runningContainerNames.contains(project.getContainerName())
                                ? ContainerStatus.WORKING
                                : ContainerStatus.NOT_WORKING;
                    }

                    // CPU 및 메모리 사용량 값이 없을 경우 "N/A"로 처리
                    String cpuUsage = Optional.ofNullable(containersResourceUsageMap.get(project.getContainerName()))
                            .map(resourceMap -> resourceMap.get("CPU"))
                            .orElse("N/A");
                    String memoryUsage = Optional.ofNullable(containersResourceUsageMap.get(project.getContainerName()))
                            .map(resourceMap -> resourceMap.get("Memory"))
                            .orElse("N/A");

                    return new ProjectResponse.ProjectDTO(
                        project.getId(),
                        project.isUrgent(),
                        project.getProjectName(),
                        project.getDescription(),
                        containerStatus,
                        cpuUsage,
                        memoryUsage);
                })
                .toList();

        return new ProjectResponse.FindProjectListDTO(projectDTOS);
    }

    @Transactional(readOnly = true)
    public ProjectResponse.FindProjectByIdDTO findProjectById(Long projectId) {
        // 존재하지 않으면 에러
        Project project = projectRepository.findById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // 최신 요약은 MySql에서 가져옴
        LocalDateTime updateTime;
        String summaryContent;

        Optional<Summary> latestSummary = summaryRepository.findLatestSummaryByProject(project, PageRequest.of(0, 1))
                .getContent()
                .stream()
                .findFirst();

        summaryContent = latestSummary.map(Summary::getContent)
                .orElse("로그 요약본이 존재하지 않습니다.");
        updateTime = latestSummary.map(Summary::getCreatedDate)
                .orElse(null);

        // 최신 로그는 ElasticSearch에서 가져옴
        String recentLog = logService.searchRecentLogInStr(project.getContainerName(), 2L);

        if (recentLog.isEmpty()) {
            recentLog = "로그값이 존재하지 않습니다";
        }

        return new ProjectResponse.FindProjectByIdDTO(
                project.getProjectName(),
                project.getDescription(),
                summaryContent,
                formatLocalDateTime(updateTime),
                recentLog);
    }

    @Transactional(readOnly = true)
    public ProjectResponse.FindProjectSummaryDTO findProjectSummary(Long projectId, Pageable pageable){
        // 존재하지 않으면 에러
        Project project = projectRepository.findById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // 최신순으로 페이지네이션
        List<Summary> summaries = summaryRepository.findByProjectId(projectId, pageable).getContent();
        List<ProjectResponse.SummaryDTO> summaryDTOS = summaries.stream()
                .map(summary -> new ProjectResponse.SummaryDTO(
                        summary.getId(),
                        formatLocalDateTime(summary.getCreatedDate()),
                        summary.getContent(),
                        summary.isChecked()))
                .toList();

        return new ProjectResponse.FindProjectSummaryDTO(
                project.getProjectName(),
                project.getDescription(),
                summaryDTOS);
    }

    @Transactional(readOnly = true)
    public ProjectResponse.FindProjectLogListDTO findProjectLogList(Long projectId){
        // projectId를 사용하여 containerName을 가져옴
        String containerName = projectRepository.findContainerNameById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // 로그 파일 목록을 가져옴
        List<String> logFiles = logService.findLogFileList();

        // containerName과 일치하는 파일만 필터링
        List<String> filteredLogFiles = logFiles.stream()
                .filter(logFile -> logFile.startsWith(containerName + "-log"))
                .toList();

        return new ProjectResponse.FindProjectLogListDTO(filteredLogFiles);
    }

    @Transactional(readOnly = true)
    public ProjectResponse.FindProjectLogByNameDTO findProjectLogByName(Long projectId, String fileName){
        Project project = projectRepository.findById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        String logMessage = logService.readLogFile(fileName);

        return new ProjectResponse.FindProjectLogByNameDTO(
                project.getProjectName(),
                project.getDescription(),
                fileName,
                logMessage
        );
    }

    // 로그 파일 삭제하는 로직 추후 추가해야함
    @Transactional
    public void deleteProjectById(Long userId, Long projectId){
        Project project = projectRepository.findById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // 권한 체크
        if(!project.getUser().getId().equals(userId)){
            throw new CustomException(ExceptionCode.USER_FORBIDDEN);
        }

        projectRepository.delete(project);
    }

    @Transactional
    public void checkSummary(Long summaryId){
        Summary summary = summaryRepository.findById(summaryId).orElseThrow(
                () -> new CustomException(ExceptionCode.SUMMARY_NOT_FOUND)
        );

        summary.updateIsChecked(!summary.isChecked());
    }

    private String formatLocalDateTime(LocalDateTime localDateTime) {
        if(localDateTime == null){
            return null;
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        return localDateTime.format(formatter);
    }
}
