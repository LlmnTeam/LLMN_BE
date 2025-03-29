package com.example.llmn.domain.project;

import com.example.llmn.domain.project.model.request.ContainerReq;
import com.example.llmn.domain.project.model.request.CreateProjectReq;
import com.example.llmn.domain.project.model.request.UpdateProjectReq;
import com.example.llmn.domain.project.model.response.*;
import com.example.llmn.security.userdetails.CustomUserDetails;
import com.example.llmn.common.utils.ApiUtils;
import com.example.llmn.domain.docker.DockerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.example.llmn.common.constants.GlobalConstants.SORT_BY_DATE;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api")
public class ProjectController {

    private final ProjectService projectService;
    private final DockerService dockerService;
    private static final boolean USING_CACHE = true;
    private static final boolean NOT_USING_CACHE = false;

    @PostMapping("/project")
    public ResponseEntity<?> createProject(@RequestBody CreateProjectReq requestDTO, @AuthenticationPrincipal CustomUserDetails userDetails) {
        CreateProjectRes responseDTO = projectService.createProject(requestDTO, userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/project/cloud")
    public ResponseEntity<?> findCloudAndContainerInfo(@AuthenticationPrincipal CustomUserDetails userDetails) {
        FindCloudAndContainerInfoRes responseDTO = projectService.findCloudAndContainerInfo(userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    // 수정 시 사용할 API
    @GetMapping("/project/{projectId}/info")
    public ResponseEntity<?> findProjectInfoById(@PathVariable Long projectId, @AuthenticationPrincipal CustomUserDetails userDetails) {
        FindProjectInfoByIdRes responseDTO = projectService.findProjectInfoById(projectId, userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PatchMapping("/project/{projectId}")
    public ResponseEntity<?> updateProject(@RequestBody UpdateProjectReq requestDTO,
                                           @PathVariable Long projectId, @AuthenticationPrincipal CustomUserDetails userDetails) {
        projectService.updateProject(requestDTO, projectId, userDetails.getUser().getId());
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @GetMapping("/project")
    public ResponseEntity<?> findProjectList(@AuthenticationPrincipal CustomUserDetails userDetails) {
        FindProjectListRes responseDTO = projectService.findProjectList(userDetails.getUser().getId(), USING_CACHE);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/project/refresh")
    public ResponseEntity<?> findRefreshedProjectList(@AuthenticationPrincipal CustomUserDetails userDetails) {
        FindProjectListRes responseDTO = projectService.findProjectList(userDetails.getUser().getId(), NOT_USING_CACHE);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/project/{projectId}")
    public ResponseEntity<?> findProjectById(@PathVariable Long projectId) {
        FindProjectByIdRes responseDTO = projectService.findProjectById(projectId);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/project/{projectId}/summaries")
    public ResponseEntity<?> findProjectSummary(@PathVariable Long projectId,
                                                @PageableDefault(size = 5, sort = SORT_BY_DATE, direction = Sort.Direction.DESC) Pageable pageable) {
        FindProjectSummaryRes responseDTO = projectService.findProjectSummary(projectId, pageable);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/project/{projectId}/logs")
    public ResponseEntity<?> findProjectLogList(@PathVariable Long projectId) {
        FindProjectLogListRes responseDTO = projectService.findProjectLogList(projectId);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/project/{projectId}/logs/{fileName}")
    public ResponseEntity<?> findProjectLogByName(@PathVariable Long projectId, @PathVariable String fileName) {
        FindProjectLogByNameRes responseDTO = projectService.findProjectLogByName(projectId, fileName);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @GetMapping("/containers")
    public ResponseEntity<?> findContainerList(@RequestParam Long sshId) {
        List<String> runningContainerNameList = dockerService.findRunningContainerList(sshId);
        FindContainerListRes responseDTO = new FindContainerListRes(runningContainerNameList);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, responseDTO));
    }

    @PostMapping("/project/{projectId}/container/stop")
    public ResponseEntity<?> stopContainer(@RequestBody ContainerReq requestDTO, @PathVariable Long projectId) {
        boolean response = dockerService.stopContainer(requestDTO.name(), projectId);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, response));
    }

    @PostMapping("/project/{projectId}/container/restart")
    public ResponseEntity<?> restartContainer(@RequestBody ContainerReq requestDTO, @PathVariable Long projectId) {
        boolean response = dockerService.restartContainer(requestDTO.name(), projectId);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, response));
    }

    @DeleteMapping("/project/{projectId}")
    public ResponseEntity<?> deleteProjectById(@PathVariable Long projectId, @AuthenticationPrincipal CustomUserDetails userDetails) {
        projectService.deleteProjectById(userDetails.getUser().getId(), projectId);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }

    @PostMapping("/summaries/{summaryId}/check")
    public ResponseEntity<?> checkSummary(@PathVariable Long summaryId) {
        projectService.checkSummary(summaryId);
        return ResponseEntity.ok().body(ApiUtils.success(HttpStatus.OK, null));
    }
}
