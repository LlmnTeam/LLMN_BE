package com.example.llmn.service;

import com.example.llmn.controller.DTO.ProjectRequest;
import com.example.llmn.controller.DTO.ProjectResponse;
import com.example.llmn.domain.ContainerStatus;
import com.example.llmn.domain.Project;
import com.example.llmn.domain.User;
import com.example.llmn.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProjectService {

    private final DockerService dockerService;
    private final ProjectRepository projectRepository;
    private final EntityManager entityManager;

    @Transactional
    public void createProject(ProjectRequest.CreateProjectDTO requestDTO, Long userId){
        // 컨테이너 이름이 들어오지 않으면 NOT_CONNECTED로 처리
        ContainerStatus containerStatus = requestDTO.containerName() != null ? ContainerStatus.NOT_WORKING : ContainerStatus.NOT_CONNECTED;

        User user = entityManager.getReference(User.class, userId);
        Project project = Project.builder()
                .user(user)
                .serviceName(requestDTO.serviceName())
                .containerName(requestDTO.containerName())
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
                        project.getServiceName(),
                        project.getDescription(),
                        project.getUpdatedDate(),
                        containerStatus);
                })
                .toList();

        return new ProjectResponse.FindProjectListDTO(projectDTOS);
    }
}
