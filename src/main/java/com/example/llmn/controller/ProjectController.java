package com.example.llmn.controller;

import com.example.llmn.controller.DTO.ProjectRequest;
import com.example.llmn.controller.DTO.ProjectResponse;
import com.example.llmn.core.security.CustomUserDetails;
import com.example.llmn.core.utils.ApiUtils;
import com.example.llmn.service.DockerService;
import com.example.llmn.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class ProjectController {

    private final ProjectService projectService;
    private final DockerService dockerService;

    @PostMapping("/repository")
    public ResponseEntity<?> createProject(@RequestBody ProjectRequest.CreateProjectDTO requestDTO, @AuthenticationPrincipal CustomUserDetails userDetails) {
        projectService.createProject(requestDTO, userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @GetMapping("/repository")
    public ResponseEntity<?> findProjectList(@AuthenticationPrincipal CustomUserDetails userDetails) {
        ProjectResponse.FindProjectListDTO responseDTO = projectService.findProjectList(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/container")
    public ResponseEntity<?> findContainerList() throws Exception {
        List<String> runningContainerNameList = dockerService.findRunningContainerNameList();
        ProjectResponse.FindContainerListDTO responseDTO = new ProjectResponse.FindContainerListDTO(runningContainerNameList);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/container/stop")
    public ResponseEntity<?> stopContainer(@RequestBody ProjectRequest.ContainerDTO requestDTO, @AuthenticationPrincipal CustomUserDetails userDetails) throws Exception {
        dockerService.stopContainerByName(requestDTO.name());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PostMapping("/container/restart")
    public ResponseEntity<?> restartContainer(@RequestBody ProjectRequest.ContainerDTO requestDTO, @AuthenticationPrincipal CustomUserDetails userDetails) throws Exception {
        dockerService.restartContainerByName(requestDTO.name());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }
}
