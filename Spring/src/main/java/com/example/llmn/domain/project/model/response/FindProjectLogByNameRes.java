package com.example.llmn.domain.project.model.response;

import com.example.llmn.domain.project.Project;

public record FindProjectLogByNameRes(
        String name,
        String description,
        String fileName,
        String logMessage) {

    public FindProjectLogByNameRes(Project project, String fileName, String logContent) {
        this(project.getProjectName(),
                project.getDescription(),
                fileName,
                logContent
        );
    }
}
