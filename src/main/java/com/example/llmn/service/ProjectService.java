package com.example.llmn.service;

import com.example.llmn.controller.DTO.ProjectRequest;
import com.example.llmn.controller.DTO.ProjectResponse;
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

    private final ProjectRepository projectRepository;
    private final EntityManager entityManager;

    @Transactional
    public void createProject(ProjectRequest.CreateProjectDTO requestDTO, Long userId){
        User user = entityManager.getReference(User.class, userId);
        Project project = Project.builder()
                .user(user)
                .serviceName(requestDTO.serviceName())
                .containerName(requestDTO.containerName())
                .description(requestDTO.description())
                .build();

        projectRepository.save(project);
    }

    @Transactional
    public ProjectResponse.FindProjectListDTO findProjectList(Long userId){
        List<Project> projects = projectRepository.findByUserId(userId);



        List<ProjectResponse.ProjectDTO> projectDTOS = projects.stream()
                .map(project -> new ProjectResponse.ProjectDTO(
                        project.getServiceName(),
                        project.getDescription(),
                        project.getUpdatedDate()))
                .toList();

        return new ProjectResponse.FindProjectListDTO(projectDTOS);
    }
}
