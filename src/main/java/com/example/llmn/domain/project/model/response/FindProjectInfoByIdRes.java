package com.example.llmn.domain.project.model.response;

import com.example.llmn.domain.project.Project;

import java.util.List;

public record FindProjectInfoByIdRes(
        String projectName,
        String usingContainerName,
        String description,
        List<ContainerDTO> containers) {

    public FindProjectInfoByIdRes(Project project, List<ContainerDTO> containers) {
        this(project.getProjectName(),
                project.getContainerName(),
                project.getDescription(),
                containers
        );
    }
}
