package com.example.llmn.service;

import com.example.llmn.controller.DTO.ProjectRequest;
import com.example.llmn.controller.DTO.ProjectResponse;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.domain.*;
import com.example.llmn.repository.ProjectRepository;
import com.example.llmn.repository.SshInfoRepository;
import com.example.llmn.repository.SummaryRepository;
import com.example.llmn.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final DockerService dockerService;
    private final LogService logService;
    private final ProjectRepository projectRepository;
    private final SummaryRepository summaryRepository;
    private final SshInfoRepository sshInfoRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final SSHService sshService;

    private static final String DOCKER_RESOURCE_KEY_CPU = "CPU";
    private static final String DOCKER_RESOURCE_KEY_MEMORY = "Memory";
    private static final String NOT_ACCESSIBLE_VALUE = "N/A";
    private static final String NOT_EXIST_SUMMARY = "";
    private static final String NOT_EXIST_LOG = "";
    private static final String SORT_BY_DATE = "createdDate";
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH");

    @Transactional
    public ProjectResponse.CreateProjectDTO createProject(ProjectRequest.CreateProjectDTO requestDTO, Long userId){
        // 컨테이너 이름이 들어오지 않으면 NOT_CONNECTED로 처리
        ContainerStatus containerStatus = requestDTO.containerName() != null
                ? ContainerStatus.NOT_WORKING
                : ContainerStatus.NOT_CONNECTED;

        // NOT_CONNECTED면 긴급으로 표시
        boolean isUrgent = (containerStatus == ContainerStatus.NOT_CONNECTED);

        User user = entityManager.getReference(User.class, userId);
        SshInfo sshInfo = entityManager.getReference(SshInfo.class, requestDTO.sshInfoId());

        Project project = Project.builder()
                .user(user)
                .sshInfo(sshInfo)
                .projectName(requestDTO.projectName())
                .containerName(requestDTO.containerName())
                .description(requestDTO.description())
                .containerStatus(containerStatus)
                .isUrgent(isUrgent)
                .build();

        projectRepository.save(project);

        return new ProjectResponse.CreateProjectDTO(project.getId());
    }

    @Transactional
    public ProjectResponse.FindCloudAndContainerInfoDTO findCloudAndContainerInfo(Long userId) {
        List<ProjectResponse.CloudInstanceDTO> cloudInstanceDTOS = new ArrayList<>();

        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
        for(SshInfo sshInfo : sshInfos){
            // 해당 클라우드 인스턴스에 실행중인 컨테이너
            List<String> runningContainers = dockerService.findRunningContainerList(sshInfo.getId());
            List<ProjectResponse.ContainerDTO> containerDTOS = runningContainers.stream()
                    .map(ProjectResponse.ContainerDTO::new)
                    .toList();

            // 클라우드 이름
            String cloudName = getSshInfoName(sshInfo);

            ProjectResponse.CloudInstanceDTO cloudInstanceDTO = new ProjectResponse.CloudInstanceDTO(cloudName, sshInfo.getId(), containerDTOS);
            cloudInstanceDTOS.add(cloudInstanceDTO);
        }

        return new ProjectResponse.FindCloudAndContainerInfoDTO(cloudInstanceDTOS);
    }

    // 수정 시 사용할 API
    @Transactional(readOnly = true)
    public ProjectResponse.FindProjectInfoByIdDTO findProjectInfoById(Long projectId, Long userId){
        // 존재하지 않으면 에러
        Project project = projectRepository.findById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // 수정 시 선택할 수 있는 컨테이너들
        List<ProjectResponse.ContainerDTO> containerDTOS = new ArrayList<>();
        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
        for(SshInfo sshInfo : sshInfos){
            List<String> runningContainers = dockerService.findRunningContainerList(sshInfo.getId());
            containerDTOS.addAll(runningContainers.stream()
                    .map(ProjectResponse.ContainerDTO::new)
                    .toList()
            );
        }

        return new ProjectResponse.FindProjectInfoByIdDTO(
                project.getProjectName(),
                project.getContainerName(),
                project.getDescription(),
                containerDTOS);
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
    public ProjectResponse.FindProjectListDTO findProjectList(Long userId, boolean isUsingCache) {
        List<Project> projects = projectRepository.findByUserIdWithSshInfo(userId);

        // 컨테이너 리소스 조회
        Map<String, Map<String, String>> containersResourceMap = dockerService.findContainersResourceUsage(projects, userId, isUsingCache);

        // 실행중인 컨테이너 목록 (Map의 키가 컨테이너 이름인 것을 활용)
        List<String> runningContainerNames = new ArrayList<>(containersResourceMap.keySet());

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
                    String cpuUsage = Optional.ofNullable(containersResourceMap.get(project.getContainerName()))
                            .map(resourceMap -> resourceMap.get(DOCKER_RESOURCE_KEY_CPU))
                            .orElse(NOT_ACCESSIBLE_VALUE);
                    String memoryUsage = Optional.ofNullable(containersResourceMap.get(project.getContainerName()))
                            .map(resourceMap -> resourceMap.get(DOCKER_RESOURCE_KEY_MEMORY))
                            .orElse(NOT_ACCESSIBLE_VALUE);

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

        // 최신 요약본 가져오기
        Pageable pageable = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, SORT_BY_DATE));
        Optional<Summary> latestSummary = summaryRepository.findLatestSummaryByProject(project, pageable)
                .getContent()
                .stream()
                .findFirst();

        String summaryContent = latestSummary.map(Summary::getContent)
                .orElse(NOT_EXIST_SUMMARY);
        LocalDateTime updateTime = latestSummary.map(Summary::getCreatedDate)
                .orElse(null);

        // 최신 로그 가져오기
        String recentLog = getRecentLog(project);

        return new ProjectResponse.FindProjectByIdDTO(
                project.getProjectName(),
                project.getDescription(),
                summaryContent,
                formatLocalDateTime(updateTime),
                recentLog);
    }

    @Transactional(readOnly = true)
    public ProjectResponse.FindProjectSummaryDTO findProjectSummary(Long projectId, Pageable pageable) {
        // 프로젝트가 존재하지 않으면 에러 발생
        Project project = projectRepository.findById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // 최신순으로 페이지네이션
        Page<Summary> summaryPage = summaryRepository.findByProjectId(projectId, pageable);
        List<ProjectResponse.SummaryDTO> summaryDTOS = summaryPage.getContent().stream()
                .map(summary -> new ProjectResponse.SummaryDTO(
                        summary.getId(),
                        formatLocalDateTime(summary.getCreatedDate()),
                        summary.getContent(),
                        summary.isChecked()))
                .toList();

        boolean isLastPage = summaryPage.isLast();

        return new ProjectResponse.FindProjectSummaryDTO(
                project.getProjectName(),
                project.getDescription(),
                summaryDTOS,
                isLastPage // 마지막 페이지 여부 반환
        );
    }

    @Transactional(readOnly = true)
    public ProjectResponse.FindProjectLogListDTO findProjectLogList(Long projectId){
        // projectId를 사용하여 containerName을 가져옴
        String containerName = projectRepository.findContainerNameById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND)
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
                () -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND)
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
                () -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND)
        );

        // 권한 체크
        if(!project.getUser().getId().equals(userId)){
            throw new CustomException(ExceptionCode.USER_FORBIDDEN);
        }
        
        summaryRepository.deleteByProjectId(projectId);
        projectRepository.delete(project);
    }

    @Transactional
    public void checkSummary(Long summaryId){
        Summary summary = summaryRepository.findById(summaryId).orElseThrow(
                () -> new CustomException(ExceptionCode.SUMMARY_NOT_FOUND)
        );

        summary.updateIsChecked(!summary.isChecked());
    }

    @Transactional
    public String executeCommandInHome(String command, boolean isFirstExecution, Long userId){
        Long monitoringSshId = userRepository.findMonitoringSshId(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        return sshService.executeCommandInShell(command, isFirstExecution, monitoringSshId);
    }

    private String formatLocalDateTime(LocalDateTime localDateTime) {
        if(localDateTime == null){
            return null;
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        return localDateTime.format(formatter);
    }

    private String getSshInfoName(SshInfo sshInfo) {
        String remoteName = sshInfo.getRemoteName() != null ? sshInfo.getRemoteName() : "Unknown Name";
        String remoteHost = sshInfo.getRemoteHost() != null ? sshInfo.getRemoteHost() : "Unknown Host";

        return String.format("%s (%s)", remoteName, remoteHost);
    }

    public String getLatestLogFile(List<String> files) {
        return files.stream()
                .max((file1, file2) -> {
                    LocalDateTime dateTime1 = extractDateTimeFromFile(file1);
                    LocalDateTime dateTime2 = extractDateTimeFromFile(file2);
                    return dateTime1.compareTo(dateTime2);  // 최신 파일을 찾기 위해 비교
                })
                .orElse(null);
    }

    private LocalDateTime extractDateTimeFromFile(String file) {
        // 파일 이름에서 "log-" 뒤부터 ".txt" 앞까지의 부분 추출
        String dateTimePart = file.substring(file.indexOf("log-") + 4, file.lastIndexOf("-"));
        return LocalDateTime.parse(dateTimePart, formatter);
    }

    private String getRecentLog(Project project){
        List<String> logFileList = logService.findLogFileList();

        String latestLogFile = logFileList.stream()
                .filter(logFile -> logFile.startsWith(project.getContainerName() + "-log"))
                .max((file1, file2) -> { // 최신 파일을 찾기 위해 비교
                    LocalDateTime dateTime1 = extractDateTimeFromFile(file1);
                    LocalDateTime dateTime2 = extractDateTimeFromFile(file2);
                    return dateTime1.compareTo(dateTime2);
                })
                .orElse(null);

        if(latestLogFile == null){
            return NOT_EXIST_LOG;
        }

        String logContent = logService.readLogFile(latestLogFile);

        return extractLastTwoLogs(logContent);
    }

    private String extractLastTwoLogs(String logContent) {
        // 날짜 패턴으로 로그를 분리
        String[] logs = logContent.split("(?=\\[\\d{4}-\\d{2}-\\d{2}_\\d{2}:\\d{2}\\])");

        // 만약 로그가 2개 이하라면 전체 로그 반환
        if (logs.length <= 2) {
            return logContent.trim();
        }

        // 마지막 두 개의 로그를 추출하여 반환
        return logs[logs.length - 2].trim() + "\n\n" + logs[logs.length - 1].trim();
    }
}
