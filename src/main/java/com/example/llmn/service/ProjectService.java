package com.example.llmn.service;

import com.example.llmn.controller.DTO.ProjectRequest;
import com.example.llmn.controller.DTO.ProjectResponse;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.domain.ContainerStatus;
import com.example.llmn.domain.Project;
import com.example.llmn.domain.User;
import com.example.llmn.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProjectService {

    private final DockerService dockerService;
    private final LogService logService;
    private final ProjectRepository projectRepository;
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

    public ProjectResponse.FindProjectByIdDTO findProjectById(Long projectId) throws IOException {
        // 존재하지 않으면 에러
        Project project = projectRepository.findById(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        String recentLog = logService.getRecentLogInStr(project.getContainerName(), 4L);

        return new ProjectResponse.FindProjectByIdDTO(project.getProjectName(), project.getDescription(), "", recentLog);
    }
}
