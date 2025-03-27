package com.example.llmn.domain.project.model.response;

import com.example.llmn.domain.project.Project;

public record FindProjectByIdRes(
        String name,
        String description,
        String summaryContent,
        String summaryUpdateDate,
        String logContent) {

    public FindProjectByIdRes(Project project, String summaryContent, String summaryUpdateTime, String recentLog) {
        this(project.getProjectName(),
                project.getDescription(),
                summaryContent,
                summaryUpdateTime,
                recentLog
        );
    }
}
