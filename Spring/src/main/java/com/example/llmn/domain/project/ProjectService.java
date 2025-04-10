package com.example.llmn.domain.project;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.docker.ContainerStatus;
import com.example.llmn.domain.log.LogService;
import com.example.llmn.domain.project.model.request.CreateProjectReq;
import com.example.llmn.domain.project.model.request.UpdateProjectReq;
import com.example.llmn.domain.project.model.response.*;
import com.example.llmn.domain.remote.ServerInstance;
import com.example.llmn.domain.summary.Summary;
import com.example.llmn.domain.remote.ServerInstanceRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static com.example.llmn.common.constants.GlobalConstants.*;
import static com.example.llmn.common.utils.DateTimeUtils.formatLocalDateTime;
import static com.example.llmn.common.utils.FileUtils.*;
import static com.example.llmn.domain.docker.DockerConstants.DOCKER_RESOURCE_KEY_CPU;
import static com.example.llmn.domain.docker.DockerConstants.DOCKER_RESOURCE_KEY_MEMORY;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProjectService {

    private final DockerService dockerService;
    private final LogService logService;
    private final ProjectRepository projectRepository;
    private final SummaryRepository summaryRepository;
    private final ServerInstanceRepository serverInstanceRepository;
    private final UserRepository userRepository;

    @Transactional
    public CreateProjectRes createProject(CreateProjectReq requestDTO, Long userId) {
        ContainerStatus containerStatus = determineContainerStatus(requestDTO);
        boolean isUrgent = containerStatus.isProjectUrgent();

        User user = userRepository.getReferenceById(userId);
        ServerInstance serverInstance = serverInstanceRepository.getReferenceById(requestDTO.sshInfoId());
        Project project = Project.builder()
                .user(user)
                .serverInstance(serverInstance)
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
        Project project = projectRepository.findByIdWithUserAndServerInstance(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        if (project.isNotOwnedBy(userId))
            throw new CustomException(ExceptionCode.USER_FORBIDDEN);

        ContainerStatus containerStatus = findContainerStatus(requestDTO.containerName(), project.getServerInstance().getId());
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
        List<ServerInstance> serverInstances = serverInstanceRepository.findByUserId(userId);
        List<ServerInstanceDTO> serverInstanceDTOS = serverInstances.stream()
                .map(this::createCloudInstanceRes)
                .toList();

        return new FindCloudAndContainerInfoRes(serverInstanceDTOS);
    }

    // 수정 시 사용할 API
    public FindProjectInfoByIdRes findProjectInfoById(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        List<ServerInstance> serverInstances = serverInstanceRepository.findByUserId(userId);
        List<ContainerDTO> selectableContainers = findSelectableContainers(serverInstances);

        return new FindProjectInfoByIdRes(project, selectableContainers);
    }

    @Transactional
    public FindProjectListRes findProjectList(Long userId, boolean isUsingCache) {
        List<Project> projects = projectRepository.findByUserIdWithServerInstance(userId);

        Map<String, Map<String, String>> containersResourceMap = dockerService.findContainersResourceUsage(projects, userId, isUsingCache);
        List<String> runningContainers = new ArrayList<>(containersResourceMap.keySet());
        List<ProjectDTO> projectDTOS = createProjectResList(projects, containersResourceMap, runningContainers);

        return new FindProjectListRes(projectDTOS);
    }

    public FindProjectByIdRes findProjectById(Long projectId) {
        Project project = projectRepository.findByIdWithServerInstance(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        String recentLog = logService.findLatestTwoLogs(project.getContainerName(), project.getServerIp());

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
        Project project = projectRepository.findByIdWithServerInstance(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND));

        List<String> logFileList = logService.findLogFilesByContainerName(project.getContainerName(), project.getServerIp());
        return new FindProjectLogListRes(logFileList);
    }

    public FindProjectLogByNameRes findProjectLogByName(Long projectId, String fileName) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND));

        String logContent = readFileAsString(fileName);

        return new FindProjectLogByNameRes(project, fileName, logContent);
    }

    public ContainerStatus findContainerStatus(String containerName, Long sshInfoId) {
        if (containerName == null) {
            return ContainerStatus.NOT_CONNECTED;
        }

        return dockerService.isContainerRunning(containerName, sshInfoId) ? ContainerStatus.WORKING : ContainerStatus.NOT_WORKING;
    }

    private ContainerStatus determineContainerStatus(CreateProjectReq requestDTO) {
        return requestDTO.containerName() != null ? ContainerStatus.NOT_WORKING : ContainerStatus.NOT_CONNECTED;
    }

    private ServerInstanceDTO createCloudInstanceRes(ServerInstance serverInstance) {
        String cloudName = getCloudName(serverInstance);
        List<ContainerDTO> runningContainerResList = dockerService.findRunningContainerList(serverInstance.getId()).stream()
                .map(ContainerDTO::new)
                .toList();

        return new ServerInstanceDTO(cloudName, serverInstance.getId(), runningContainerResList);
    }

    private String getCloudName(ServerInstance serverInstance) {
        String remoteName = serverInstance.getRemoteName() != null ? serverInstance.getRemoteName() : "Unknown Name";
        String remoteHost = serverInstance.getRemoteHost() != null ? serverInstance.getRemoteHost() : "Unknown Host";
        return String.format("%s (%s)", remoteName, remoteHost);
    }

    private List<ContainerDTO> findSelectableContainers(List<ServerInstance> serverInstances) {
        return serverInstances.stream()
                .flatMap(sshInfo -> dockerService.findRunningContainerList(sshInfo.getId()).stream())
                .map(ContainerDTO::new)
                .toList();
    }

    private List<ProjectDTO> createProjectResList(List<Project> projects, Map<String, Map<String, String>> containersResourceMap,
                                                  List<String> runningContainers) {
        return projects.stream()
                .map(project -> {
                    ContainerStatus containerStatus = getContainerStatus(runningContainers, project);
                    String cpuUsage = getResourceUsageFromMap(containersResourceMap, project.getContainerName(), DOCKER_RESOURCE_KEY_CPU);
                    String memoryUsage = getResourceUsageFromMap(containersResourceMap, project.getContainerName(), DOCKER_RESOURCE_KEY_MEMORY);
                    return new ProjectDTO(project, containerStatus, cpuUsage, memoryUsage);
                })
                .toList();
    }

    private ContainerStatus getContainerStatus(List<String> runningContainerList, Project project) {
        if (project.getContainerStatus() != ContainerStatus.NOT_CONNECTED) {
            return runningContainerList.contains(project.getContainerName())
                    ? ContainerStatus.WORKING
                    : ContainerStatus.NOT_WORKING;
        }

        return ContainerStatus.NOT_CONNECTED;
    }

    private Optional<Summary> findLatestSummary(Project project) {
        Pageable pageable = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, SORT_BY_DATE));
        return summaryRepository.findByProject(project, pageable)
                .getContent()
                .stream()
                .findFirst();
    }

    private String getContentFromSummary(Optional<Summary> latestSummaryOP) {
        return latestSummaryOP.map(Summary::getContent).orElse(BLANK_STRING);
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

    private String getResourceUsageFromMap(Map<String, Map<String, String>> containersResourceMap, String containerName, String resourceKey) {
        return Optional.ofNullable(containersResourceMap.get(containerName))
                .map(resourceMap -> resourceMap.get(resourceKey))
                .orElse(NOT_AVAILABLE);
    }
}