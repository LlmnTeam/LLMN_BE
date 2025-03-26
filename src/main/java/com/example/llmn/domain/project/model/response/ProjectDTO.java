package com.example.llmn.domain.project.model.response;

import com.example.llmn.domain.docker.ContainerStatus;
import com.example.llmn.domain.project.Project;

public record ProjectDTO(
        Long id,
        boolean isUrgent,
        String name,
        String description,
        ContainerStatus containerStatus,
        String cpuUsage,
        String memoryUsage) {

    public ProjectDTO(Project project, ContainerStatus containerStatus, String cpuUsage, String memoryUsage) {
        this(project.getId(),
                project.isUrgent(),
                project.getProjectName(),
                project.getDescription(),
                containerStatus,
                cpuUsage,
                memoryUsage
        );
    }
}
