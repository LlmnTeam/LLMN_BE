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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.example.llmn.core.utils.DateTimeUtils.formatLocalDateTime;
import static com.example.llmn.core.utils.FileUtils.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final DockerService dockerService;
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
        ContainerStatus containerStatus = determineContainerStatus(requestDTO);
        boolean isUrgent = containerStatus.isProjectUrgent();;

        User user = getUserReference(userId);
        SshInfo sshInfo = getSshInfoReference(requestDTO.sshInfoId());
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
        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
        List<ProjectResponse.CloudInstanceDTO> cloudInstanceDTOS = sshInfos.stream()
                .map(this::createCloudInstanceDTO)
                .toList();

        return new ProjectResponse.FindCloudAndContainerInfoDTO(cloudInstanceDTOS);
    }

    // 수정 시 사용할 API
    @Transactional(readOnly = true)
    public ProjectResponse.FindProjectInfoByIdDTO findProjectInfoById(Long projectId, Long userId){
        Project project = projectRepository.findById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // 수정 시 선택할 수 있는 컨테이너들
        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
        List<ProjectResponse.ContainerDTO> selectableContainers = createContainerDTOS(sshInfos);

        return new ProjectResponse.FindProjectInfoByIdDTO(
                project.getProjectName(),
                project.getContainerName(),
                project.getDescription(),
                selectableContainers);
    }

    @Transactional
    public void updateProject(ProjectRequest.UpdateProjectDTO requestDTO, Long projectId, Long userId){
        Project project = projectRepository.findByIdWithUserAndSshInfo(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        // 권한 체크
        if(project.isNotOwnedBy(userId)){
            throw new CustomException(ExceptionCode.USER_FORBIDDEN);
        }

        ContainerStatus containerStatus = determineContainerStatus(requestDTO, project.getSshInfo().getId());
        project.updateProject(requestDTO.projectName(), requestDTO.containerName(), requestDTO.description(), containerStatus);
    }

    @Transactional
    public ProjectResponse.FindProjectListDTO findProjectList(Long userId, boolean isUsingCache) {
        List<Project> projects = projectRepository.findByUserIdWithSshInfo(userId);

        // 실행중인 컨테이너의 리소스 조회 => 맵으로 변환
        Map<String, Map<String, String>> containersResourceMap = dockerService.findContainersResourceUsage(projects, userId, isUsingCache);

        // 실행중인 컨테이너 목록 (리소스 맵의 키가 실행중인 컨테이너 이름인 것을 활용)
        List<String> runningContainers = new ArrayList<>(containersResourceMap.keySet());

        List<ProjectResponse.ProjectDTO> projectDTOS = projects.stream()
                .map(project -> {
                    ContainerStatus containerStatus = determineContainerStatus(runningContainers, project);
                    String cpuUsage = getResourceUsageFromMap(containersResourceMap, project, DOCKER_RESOURCE_KEY_CPU);
                    String memoryUsage = getResourceUsageFromMap(containersResourceMap, project, DOCKER_RESOURCE_KEY_MEMORY);

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

        return new ProjectResponse.FindProjectSummaryDTO(
                project.getProjectName(),
                project.getDescription(),
                summaryDTOS,
                summaryPage.isLast(),
                summaryPage.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public ProjectResponse.FindProjectLogListDTO findProjectLogList(Long projectId){
        // projectId를 사용하여 containerName을 가져옴
        String containerName = projectRepository.findContainerNameById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND)
        );

        // 로그 파일 목록을 가져옴
        List<String> logFiles = getFileList(LOGS_DIRECTORY);

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

        String logContent = readFileAsString(fileName);

        return new ProjectResponse.FindProjectLogByNameDTO(
                project.getProjectName(),
                project.getDescription(),
                fileName,
                logContent
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

    @Transactional
    @Scheduled(cron = "0 0 0,12 * * *")
    public void initEmergency() {
        List<Project> projects = projectRepository.findAll();
        projects.forEach(project -> project.updateIsUrgent(false));
    }

    private String getCloudName(SshInfo sshInfo) {
        String remoteName = sshInfo.getRemoteName() != null ? sshInfo.getRemoteName() : "Unknown Name";
        String remoteHost = sshInfo.getRemoteHost() != null ? sshInfo.getRemoteHost() : "Unknown Host";

        return String.format("%s (%s)", remoteName, remoteHost);
    }

    public String getLatestLogFile(List<String> files) {
        return files.stream()
                .max((file1, file2) -> {
                    LocalDateTime dateTime1 = parseDateTimeFromFileName(file1);
                    LocalDateTime dateTime2 = parseDateTimeFromFileName(file2);
                    return dateTime1.compareTo(dateTime2);  // 최신 파일을 찾기 위해 비교
                })
                .orElse(null);
    }

    private LocalDateTime parseDateTimeFromFileName(String file) {
        // 파일 이름에서 "log-" 뒤부터 ".txt" 앞까지의 부분 추출
        String dateTimePart = file.substring(file.indexOf("log-") + 4, file.lastIndexOf("-"));
        return LocalDateTime.parse(dateTimePart, formatter);
    }

    private String getRecentLog(Project project){
        List<String> logFiles = getFileList(LOGS_DIRECTORY);

        String latestLogFile = logFiles.stream()
                .filter(logFile -> logFile.startsWith(project.getContainerName() + "-log"))
                .max(this::compareLogFileDates) // 최신 파일 찾기
                .orElse(null);

        if(latestLogFile == null){
            return NOT_EXIST_LOG;
        }

        String logContent = readFileAsString(latestLogFile);
        return parseLastTwoLogs(logContent);
    }

    private int compareLogFileDates(String file1, String file2) {
        LocalDateTime fileDateTime1 = parseDateTimeFromFileName(file1);
        LocalDateTime fileDateTime2 = parseDateTimeFromFileName(file2);
        return fileDateTime1.compareTo(fileDateTime2);
    }

    private String parseLastTwoLogs(String logContent) {
        // 날짜 패턴으로 로그를 분리
        String[] logs = logContent.split("(?=\\[\\d{4}-\\d{2}-\\d{2}_\\d{2}:\\d{2}\\])");

        // 만약 로그가 2개 이하라면 전체 로그 반환
        if (logs.length <= 2) {
            return logContent.trim();
        }

        // 마지막 두 개의 로그를 추출하여 반환
        return logs[logs.length - 2].trim() + "\n\n" + logs[logs.length - 1].trim();
    }

    private ContainerStatus determineContainerStatus( List<String> runningContainerNames, Project project) {
        // 연결된 상태라면, 실행중인 컨테이너 목록에 속해있는지 체크
        if (project.getContainerStatus() != ContainerStatus.NOT_CONNECTED) {
            return runningContainerNames.contains(project.getContainerName())
                    ? ContainerStatus.WORKING
                    : ContainerStatus.NOT_WORKING;
        }
        return ContainerStatus.NOT_CONNECTED;
    }

    private String getResourceUsageFromMap(Map<String, Map<String, String>> containersResourceMap, Project project, String resourceKey) {
        return Optional.ofNullable(containersResourceMap.get(project.getContainerName()))
                .map(resourceMap -> resourceMap.get(resourceKey))
                .orElse(NOT_ACCESSIBLE_VALUE); // CPU 및 메모리 사용량 값이 없을 경우 "N/A"로 처리
    }

    private ContainerStatus determineContainerStatus(ProjectRequest.CreateProjectDTO requestDTO) {
        // 요청에 컨테이너 이름이 들어오지 않으면 NOT_CONNECTED로 처리
        return requestDTO.containerName() != null ? ContainerStatus.NOT_WORKING : ContainerStatus.NOT_CONNECTED;
    }

    private User getUserReference(Long userId) {
        return entityManager.getReference(User.class, userId);
    }

    private SshInfo getSshInfoReference(Long sshInfoId) {
        return entityManager.getReference(SshInfo.class, sshInfoId);
    }

    private ProjectResponse.CloudInstanceDTO createCloudInstanceDTO(SshInfo sshInfo) {
        // 클라우드 이름
        String cloudName = getCloudName(sshInfo);

        // 실행 중인 컨테이너 정보
        List<ProjectResponse.ContainerDTO> containerDTOS = dockerService.findRunningContainerList(sshInfo.getId()).stream()
                .map(ProjectResponse.ContainerDTO::new)
                .toList();

        return new ProjectResponse.CloudInstanceDTO(cloudName, sshInfo.getId(), containerDTOS);
    }

    private List<ProjectResponse.ContainerDTO> createContainerDTOS(List<SshInfo> sshInfos){
        return sshInfos.stream()
                .flatMap(sshInfo -> dockerService.findRunningContainerList(sshInfo.getId()).stream())
                .map(ProjectResponse.ContainerDTO::new)
                .toList();
    }

    private ContainerStatus determineContainerStatus(ProjectRequest.UpdateProjectDTO requestDTO, Long sshInfoId) {
        if(requestDTO.containerName() == null){
            return ContainerStatus.NOT_CONNECTED;
        }

        return dockerService.isContainerRunning(requestDTO.containerName(), sshInfoId) ? ContainerStatus.WORKING : ContainerStatus.NOT_WORKING;
    }
}
