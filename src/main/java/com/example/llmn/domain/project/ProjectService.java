package com.example.llmn.domain.project;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.docker.ContainerStatus;
import com.example.llmn.domain.project.model.request.CreateProjectReq;
import com.example.llmn.domain.project.model.request.UpdateProjectReq;
import com.example.llmn.domain.project.model.response.*;
import com.example.llmn.domain.remote.SshInfo;
import com.example.llmn.domain.summary.Summary;
import com.example.llmn.domain.remote.SshInfoRepository;
import com.example.llmn.domain.summary.SummaryRepository;
import com.example.llmn.domain.user.User;
import com.example.llmn.domain.docker.DockerService;
import com.example.llmn.domain.user.UserRepository;
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
import java.util.*;

import static com.example.llmn.common.constants.GlobalConstants.NOT_AVAILABLE;
import static com.example.llmn.common.constants.GlobalConstants.SORT_BY_DATE;
import static com.example.llmn.common.utils.DateTimeUtils.formatLocalDateTime;
import static com.example.llmn.common.utils.DateTimeUtils.parseDateTimeFromLogFile;
import static com.example.llmn.common.utils.FileUtils.*;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProjectService {

    private final DockerService dockerService;
    private final ProjectRepository projectRepository;
    private final SummaryRepository summaryRepository;
    private final SshInfoRepository sshInfoRepository;
    private final UserRepository userRepository;

    private static final String DOCKER_RESOURCE_KEY_CPU = "CPU";
    private static final String DOCKER_RESOURCE_KEY_MEMORY = "Memory";
    private static final String NOT_EXIST_SUMMARY = "";
    private static final String NOT_EXIST_LOG = "";
    private static final String LOG_FILE_SUFFIX = "-log";

    @Transactional
    @Scheduled(cron = "0 0 0,12 * * *")
    public void initEmergency() {
        List<Project> projects = projectRepository.findAll();
        projects.forEach(project -> project.updateIsUrgent(false));
    }

    @Transactional
    public CreateProjectRes createProject(CreateProjectReq requestDTO, Long userId) {
        ContainerStatus containerStatus = determineContainerStatus(requestDTO);
        boolean isUrgent = containerStatus.isProjectUrgent();

        User user = userRepository.getReferenceById(userId);
        SshInfo sshInfo = sshInfoRepository.getReferenceById(requestDTO.sshInfoId());
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

        return new CreateProjectRes(project.getId());
    }

    @Transactional
    public void updateProject(UpdateProjectReq requestDTO, Long projectId, Long userId) {
        Project project = projectRepository.findByIdWithUserAndSshInfo(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        if (project.isNotOwnedBy(userId)) {
            throw new CustomException(ExceptionCode.USER_FORBIDDEN);
        }

        ContainerStatus containerStatus = findContainerStatus(requestDTO, project.getSshInfo().getId());
        project.updateProject(requestDTO.projectName(), requestDTO.containerName(), requestDTO.description(), containerStatus);
    }

    @Transactional
    public void checkSummary(Long summaryId) {
        Summary summary = summaryRepository.findById(summaryId)
                .orElseThrow(() -> new CustomException(ExceptionCode.SUMMARY_NOT_FOUND));

        summary.check();
    }

    // 로그 파일 삭제하는 로직 추후 추가해야함
    @Transactional
    public void deleteProjectById(Long userId, Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND));

        if (project.isNotOwnedBy(userId)) {
            throw new CustomException(ExceptionCode.USER_FORBIDDEN);
        }

        summaryRepository.deleteByProjectId(projectId);
        projectRepository.delete(project);
    }

    @Transactional
    public FindCloudAndContainerInfoRes findCloudAndContainerInfo(Long userId) {
        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
        List<CloudInstanceDTO> cloudInstanceDTOS = sshInfos.stream()
                .map(this::createCloudInstanceRes)
                .toList();

        return new FindCloudAndContainerInfoRes(cloudInstanceDTOS);
    }

    // 수정 시 사용할 API
    public FindProjectInfoByIdRes findProjectInfoById(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        List<SshInfo> sshInfos = sshInfoRepository.findByUserId(userId);
        List<ContainerDTO> selectableContainers = findSelectableContainers(sshInfos);

        return new FindProjectInfoByIdRes(project, selectableContainers);
    }

    @Transactional
    public FindProjectListRes findProjectList(Long userId, boolean isUsingCache) {
        List<Project> projects = projectRepository.findByUserIdWithSshInfo(userId);

        Map<String, Map<String, String>> containersResourceMap = dockerService.findContainersResourceUsage(projects, userId, isUsingCache);
        List<String> runningContainers = new ArrayList<>(containersResourceMap.keySet());
        List<ProjectDTO> projectDTOS = createProjectResList(projects, containersResourceMap, runningContainers);

        return new FindProjectListRes(projectDTOS);
    }

    public FindProjectByIdRes findProjectById(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        String recentLog = findRecentLog(project);

        Optional<Summary> latestSummaryOP = findLatestSummary(project);
        String summaryContent = getContentFromSummary(latestSummaryOP);
        LocalDateTime summaryUpdateTime = getUpdateTimeFromSummary(latestSummaryOP);

        return new FindProjectByIdRes(
                project,
                summaryContent,
                formatLocalDateTime(summaryUpdateTime),
                recentLog);
    }

    public FindProjectSummaryRes findProjectSummary(Long projectId, Pageable pageable) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        Page<Summary> summaryPage = summaryRepository.findByProjectId(projectId, pageable);
        List<SummaryDTO> summaryResList = createSummaryResList(summaryPage.getContent());

        return new FindProjectSummaryRes(project, summaryResList, summaryPage);
    }

    public FindProjectLogListRes findProjectLogList(Long projectId) {
        String containerName = projectRepository.findContainerNameById(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND));

        List<String> logFileList = findLogFilesByContainerName(containerName);
        return new FindProjectLogListRes(logFileList);
    }

    public FindProjectLogByNameRes findProjectLogByName(Long projectId, String fileName) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND));

        String logContent = readFileAsString(fileName);

        return new FindProjectLogByNameRes(project, fileName, logContent);
    }

    private ContainerStatus determineContainerStatus(CreateProjectReq requestDTO) {
        return requestDTO.containerName() != null ? ContainerStatus.NOT_WORKING : ContainerStatus.NOT_CONNECTED;
    }

    private ContainerStatus findContainerStatus(UpdateProjectReq requestDTO, Long sshInfoId) {
        if (requestDTO.containerName() == null) {
            return ContainerStatus.NOT_CONNECTED;
        }

        return dockerService.isContainerRunning(requestDTO.containerName(), sshInfoId) ? ContainerStatus.WORKING : ContainerStatus.NOT_WORKING;
    }

    private CloudInstanceDTO createCloudInstanceRes(SshInfo sshInfo) {
        String cloudName = getCloudName(sshInfo);
        List<ContainerDTO> runningContainerResList = dockerService.findRunningContainerList(sshInfo.getId()).stream()
                .map(ContainerDTO::new)
                .toList();

        return new CloudInstanceDTO(cloudName, sshInfo.getId(), runningContainerResList);
    }

    private String getCloudName(SshInfo sshInfo) {
        String remoteName = sshInfo.getRemoteName() != null ? sshInfo.getRemoteName() : "Unknown Name";
        String remoteHost = sshInfo.getRemoteHost() != null ? sshInfo.getRemoteHost() : "Unknown Host";
        return String.format("%s (%s)", remoteName, remoteHost);
    }

    private List<ContainerDTO> findSelectableContainers(List<SshInfo> sshInfos) {
        return sshInfos.stream()
                .flatMap(sshInfo -> dockerService.findRunningContainerList(sshInfo.getId()).stream())
                .map(ContainerDTO::new)
                .toList();
    }

    private List<ProjectDTO> createProjectResList(List<Project> projects, Map<String, Map<String, String>> containersResourceMap, List<String> runningContainers) {
        return projects.stream()
                .map(project -> createProjectRes(containersResourceMap, runningContainers, project))
                .toList();
    }

    private ProjectDTO createProjectRes(Map<String, Map<String, String>> containersResourceMap, List<String> runningContainers, Project project) {
        ContainerStatus containerStatus = getContainerStatus(runningContainers, project);
        String cpuUsage = getResourceUsageFromMap(containersResourceMap, project.getContainerName(), DOCKER_RESOURCE_KEY_CPU);
        String memoryUsage = getResourceUsageFromMap(containersResourceMap, project.getContainerName(), DOCKER_RESOURCE_KEY_MEMORY);

        return new ProjectDTO(project, containerStatus, cpuUsage, memoryUsage);
    }

    private ContainerStatus getContainerStatus(List<String> runningContainerList, Project project) {
        if (project.getContainerStatus() != ContainerStatus.NOT_CONNECTED) {
            return runningContainerList.contains(project.getContainerName())
                    ? ContainerStatus.WORKING
                    : ContainerStatus.NOT_WORKING;
        }

        return ContainerStatus.NOT_CONNECTED;
    }

    private String findRecentLog(Project project) {
        String latestLogFile = findTextFiles(LOGS_DIRECTORY).stream()
                .filter(logFile -> logFile.startsWith(project.getContainerName() + "-log"))
                .max(this::compareLogFileDates) // 최신 파일 찾기
                .orElse(null);

        if (latestLogFile == null) {
            return NOT_EXIST_LOG;
        }

        return parseLastTwoLogs(readFileAsString(latestLogFile));
    }

    private int compareLogFileDates(String file1, String file2) {
        LocalDateTime fileDateTime1 = parseDateTimeFromLogFile(file1);
        LocalDateTime fileDateTime2 = parseDateTimeFromLogFile(file2);
        return fileDateTime1.compareTo(fileDateTime2);
    }

    private String parseLastTwoLogs(String logContent) {
        String[] logs = splitLogsByDatePattern(logContent);
        return logs.length <= 2 ? logContent.trim() : formatLastTwoLogs(logs);
    }

    private String[] splitLogsByDatePattern(String logContent) {
        return logContent.split("(?=\\[\\d{4}-\\d{2}-\\d{2}_\\d{2}:\\d{2}\\])");
    }

    private String formatLastTwoLogs(String[] logs) {
        String lastLog = logs[logs.length - 1].trim();
        String secondLastLog = logs[logs.length - 2].trim();
        return secondLastLog + "\n\n" + lastLog;
    }

    private Optional<Summary> findLatestSummary(Project project) {
        Pageable pageable = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, SORT_BY_DATE));
        return summaryRepository.findByProject(project, pageable)
                .getContent()
                .stream()
                .findFirst();
    }

    private String getContentFromSummary(Optional<Summary> latestSummaryOP) {
        return latestSummaryOP.map(Summary::getContent).orElse(NOT_EXIST_SUMMARY);
    }

    private LocalDateTime getUpdateTimeFromSummary(Optional<Summary> latestSummaryOP) {
        return latestSummaryOP.map(Summary::getCreatedDate).orElse(null);
    }

    private List<SummaryDTO> createSummaryResList(List<Summary> summaries) {
        return summaries.stream()
                .map(summary -> new SummaryDTO(
                        summary.getId(),
                        formatLocalDateTime(summary.getCreatedDate()),
                        summary.getContent(),
                        summary.isChecked()))
                .toList();
    }

    private List<String> findLogFilesByContainerName(String containerName) {
        return findTextFiles(LOGS_DIRECTORY).stream()
                .filter(logFile -> logFile.startsWith(containerName + LOG_FILE_SUFFIX))
                .toList();
    }

    private String getResourceUsageFromMap(Map<String, Map<String, String>> containersResourceMap, String containerName, String resourceKey) {
        return Optional.ofNullable(containersResourceMap.get(containerName))
                .map(resourceMap -> resourceMap.get(resourceKey))
                .orElse(NOT_AVAILABLE);
    }
}