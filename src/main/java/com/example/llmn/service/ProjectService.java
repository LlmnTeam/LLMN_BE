package com.example.llmn.service;

import com.example.llmn.controller.DTO.LogDTO;
import com.example.llmn.controller.DTO.ProjectRequest;
import com.example.llmn.controller.DTO.ProjectResponse;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.domain.ContainerStatus;
import com.example.llmn.domain.Project;
import com.example.llmn.domain.Summary;
import com.example.llmn.domain.User;
import com.example.llmn.repository.ProjectRepository;
import com.example.llmn.repository.SummaryRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProjectService {

    private final DockerService dockerService;
    private final LogService logService;
    private final LlmService llmService;
    private final ProjectRepository projectRepository;
    private final SummaryRepository summaryRepository;
    private final EntityManager entityManager;

    @Transactional
    public void createProject(ProjectRequest.CreateProjectDTO requestDTO, Long userId){
        // 컨테이너 이름이 들어오지 않으면 NOT_CONNECTED로 처리
        ContainerStatus containerStatus = requestDTO.containerName() != null
                ? ContainerStatus.NOT_WORKING
                : ContainerStatus.NOT_CONNECTED;

        User user = entityManager.getReference(User.class, userId);
        Project project = Project.builder()
                .user(user)
                .projectName(requestDTO.serviceName())
                .containerName(requestDTO.containerName())
                .isLocalContainer(requestDTO.isLocalContainer())
                .description(requestDTO.description())
                .containerStatus(containerStatus)
                .build();

        projectRepository.save(project);
    }

    @Transactional
    public ProjectResponse.FindProjectListDTO findProjectList(Long userId) throws Exception {
        List<Project> projects = projectRepository.findByUserId(userId);

        // 실행중인 컨테이너 목록 조회
        List<String> runningContainerNames = dockerService.findRunningContainerNameList();

        // 컨테이너 리소스 조회
        Map<String, Map<String, String>> containersResourceUsageMap = dockerService.findAllContainersResourceUsage();

        List<ProjectResponse.ProjectDTO> projectDTOS = projects.stream()
                .map(project -> {
                    ContainerStatus containerStatus = project.getContainerStatus();

                    // 연결된 상태라면 도커 상태 체크
                    if (containerStatus != ContainerStatus.NOT_CONNECTED) {
                        containerStatus = runningContainerNames.contains(project.getContainerName())
                                ? ContainerStatus.WORKING
                                : ContainerStatus.NOT_WORKING;
                    }

                    return new ProjectResponse.ProjectDTO(
                        project.getId(),
                        project.isWorking(),
                        project.getProjectName(),
                        project.getDescription(),
                        project.getUpdatedDate(),
                        containerStatus,
                        containersResourceUsageMap.get(project.getContainerName()).get("CPU"),
                        containersResourceUsageMap.get(project.getContainerName()).get("Memory"));
                })
                .toList();

        return new ProjectResponse.FindProjectListDTO(projectDTOS);
    }

    @Transactional(readOnly = true)
    public ProjectResponse.FindProjectByIdDTO findProjectById(Long projectId) throws IOException {
        // 존재하지 않으면 에러
        Project project = projectRepository.findById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // 최신 요약은 MySQL에서 가져옴
        Pageable pageable = PageRequest.of(0, 1);
        String recentSummary = summaryRepository.findLatestSummaryByProject(project, pageable).getContent().get(0);

        if(recentSummary.isEmpty()){
            recentSummary = "로그 요약본이 존재하지 않습니다.";
        }

        // 최신 로그는 ElasticSearch에서 가져옴
        String recentLog = logService.searchRecentLogInStr(project.getContainerName(), 2L);

        if (recentLog.isEmpty()) {
            recentLog = "로그값이 존재하지 않습니다";
        }

        return new ProjectResponse.FindProjectByIdDTO(
                project.getProjectName(),
                project.getDescription(),
                recentSummary,
                recentLog);
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

    @Transactional
    @Scheduled(cron = "0 0,30 * * * *")
    public void summaryLog(){
        List<Project> projects = projectRepository.findAll();

        // 혐재 시간과 30분 전
        Instant endTime = Instant.now();
        Instant startTime = endTime.minus(30, ChronoUnit.MINUTES);

        projects.stream()
                .filter(project -> !project.getContainerStatus().equals(ContainerStatus.NOT_CONNECTED))
                .forEach(project -> {
                    LogDTO.SummaryResponseDTO summaryDTO = llmService.fetchLogSummary(startTime, endTime, project.getContainerName());

                    // 검색 결과가 빈 값이라 요약 값이 없는 경우
                    if(summaryDTO == null){
                        return;
                    }

                    Summary generalSummary = new Summary(project, summaryDTO.generalSummary());
                    summaryRepository.save(generalSummary);
                    Summary anomalySummary = new Summary(project, summaryDTO.anomalySummary());
                    summaryRepository.save(anomalySummary);
                });
    }
}
