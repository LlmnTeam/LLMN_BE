package com.example.llmn.controller.DTO;

import com.example.llmn.domain.ContainerStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public class ProjectResponse {

    public record FindProjectListDTO(List<ProjectDTO> projects){}

    public record ProjectDTO(
            Long id,
            boolean isWorking,
            String name,
            String description,
            LocalDateTime updateTime,
            ContainerStatus containerStatus,
            String cpuUsage,
            String memoryUsage) {}

    public record FindContainerListDTO(List<String> names){}

    public record FindProjectByIdDTO(
            String name,
            String description,
            String summery,
            String recentLog){}
}
