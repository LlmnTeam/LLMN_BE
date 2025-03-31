package com.example.llmn.domain.project;

import com.example.llmn.domain.docker.ContainerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectScheduler {

    private final ProjectRepository projectRepository;
    private final ProjectService projectService;

    @Transactional
    @Scheduled(cron = "0 0 */3 * * *")
    public void initEmergency() {
        List<Project> projects = projectRepository.findAll();
        projects.forEach(project -> project.updateIsUrgent(false));
    }

    @Transactional
    @Scheduled(cron = "0 8 * * * *")
    public void checkContainerStatus() {
        List<Project> projects = projectRepository.findAllWithSshInfo();
        projects.forEach(project -> {
            ContainerStatus containerStatus = projectService.findContainerStatus(project.getContainerName(), project.getSshInfoId());
            project.updateContainerStatus(containerStatus);
        });
    }
}