package com.example.llmn.controller.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public class ProjectResponse {

    public record FindProjectListDTO(List<ProjectDTO> projects){}

    public record ProjectDTO(
            String name,
            String description,
            LocalDateTime updateTime) {}

    public record FindContainerListDTO(List<String> names){}
}
