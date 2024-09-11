package com.example.llmn.controller.DTO;

import com.example.llmn.domain.ContainerStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public class ProjectResponse {

    public record FindProjectListDTO(List<ProjectDTO> projects){}

    public record ProjectDTO(
            String name,
            String description,
            LocalDateTime updateTime,
            ContainerStatus containerStatus) {}

    public record FindContainerListDTO(List<String> names){}
}
