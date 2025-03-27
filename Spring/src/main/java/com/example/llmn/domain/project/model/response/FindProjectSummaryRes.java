package com.example.llmn.domain.project.model.response;

import com.example.llmn.domain.project.Project;
import com.example.llmn.domain.summary.Summary;
import org.springframework.data.domain.Page;

import java.util.List;

public record FindProjectSummaryRes(
        String name,
        String description,
        List<SummaryDTO> summaries,
        boolean isLastPage,
        int pageNum) {

    public FindProjectSummaryRes(Project project, List<SummaryDTO> summaryResList, Page<Summary> summaryPage) {
        this(project.getProjectName(),
                project.getDescription(),
                summaryResList,
                summaryPage.isLast(),
                summaryPage.getTotalPages()
        );
    }
}
