package com.example.llmn.service;

import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.domain.Project;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DockerService {

    private final SSHService sshService;
    private final RedisService redisService;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    private static final String RESOURCE_KEY = "resource";
    public static final String DOCKER_RESOURCE_KEY_CPU = "CPU";
    public static final String DOCKER_RESOURCE_KEY_MEMORY = "Memory";
    public static final String COMMAND_CONTAINER_STOP = "docker stop ";
    public static final String COMMAND_CONTAINER_RESTART = "docker restart ";
    public static final String COMMAND_CONTAINER_PS = "docker ps --format \"{{.Names}}\"";
    public static final String COMMAND_CONTAINER_STATS = "docker stats --no-stream --format \"{{.Name}}:{{.CPUPerc}}:{{.MemUsage}}\"";
    private static final Long RESOURCE_EXP = 10 * 60 * 1000L; // 10분
    private static final String BLANK_STRING = "";

    public boolean stopContainer(String containerName, Long projectId) {
        Long sshInfoId = projectRepository.findSshInfoId(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND)
        );

        String command = buildContainerCommand(COMMAND_CONTAINER_STOP, containerName);
        String commandResponse = sshService.executeCommandOnce(command, sshInfoId);

        return isCommandSuccess(containerName, commandResponse);
    }

    public boolean restartContainer(String containerName, Long projectId) {
        Long sshInfoId = projectRepository.findSshInfoId(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND)
        );

        String command = buildContainerCommand(COMMAND_CONTAINER_RESTART, containerName);
        String commandResponse = sshService.executeCommandOnce(command, sshInfoId);

        return isCommandSuccess(containerName, commandResponse);
    }

    public List<String> findRunningContainerList(Long sshInfoId) {
        String commandResponse = sshService.executeCommandOnce(COMMAND_CONTAINER_PS, sshInfoId);
        return parseContainerList(commandResponse);
    }

    public boolean isContainerRunning(String containerName, Long sshInfoId) {
        return findRunningContainerList(sshInfoId).stream()
                .anyMatch(name -> name.equals(containerName));
    }

    public Map<String, Map<String, String>> findContainersResourceUsage(List<Project> projects, Long userId, boolean isUsingCache) {
        Map<String, Map<String, String>> cachedResourceUsage = retrieveCachedResourceUsage(isUsingCache, userId);
        if (hasCachedValue(cachedResourceUsage)) {
            return cachedResourceUsage;
        }

        List<SshInfo> sshInfos = extractUniqueSshInfos(projects);
        Map<String, Map<String, String>> resourceUsage = fetchResourceUsageFromSsh(sshInfos);

        cacheResourceUsage(userId, resourceUsage);

        return resourceUsage;
    }

    private Map<String, Map<String, String>> retrieveCachedResourceUsage(boolean isUsingCache, Long userId) {
        if (!isUsingCache) {
            return Collections.emptyMap();
        }

        String cachedValue = redisService.getValueInString(RESOURCE_KEY, userId.toString());

        if (cachedValue == null) {
            return Collections.emptyMap();
        }

        return convertStringToMetricMap(cachedValue);
    }

    private boolean hasCachedValue(Map<String, Map<String, String>> cachedResourceUsage) {
        return !cachedResourceUsage.isEmpty();
    }

    private Map<String, Map<String, String>> convertStringToMetricMap(String cachedValue) {
        try {
            return objectMapper.readValue(cachedValue, new TypeReference<Map<String, Map<String, String>>>() {});
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }

    private Map<String, Map<String, String>> fetchResourceUsageFromSsh(List<SshInfo> sshInfos) {
        Map<String, Map<String, String>> resourceUsageMap = new HashMap<>();

        for (SshInfo sshInfo : sshInfos) {
            String commandResponse = sshService.executeCommandOnce(COMMAND_CONTAINER_STATS, sshInfo.getId());
            Map<String, Map<String, String>> parsedUsage = parseCommandResponse(commandResponse);
            resourceUsageMap.putAll(parsedUsage);
        }

        return resourceUsageMap;
    }

    private Map<String, Map<String, String>> parseCommandResponse(String commandResponse) {
        Map<String, Map<String, String>> containerUsageMap = new HashMap<>();

        // command 결과를 줄 단위로 나눔
        String[] lines = commandResponse.split("\n");

        // 각 줄을 ':'로 나누어 컨테이너 이름, CPU, 메모리 사용량을 추출
        for (String line : lines) {
            String[] parts = line.split(":");

            if (parts.length == 3) {
                String containerName = parts[0].trim();
                String cpuUsage = parts[1].trim();
                String memUsage = parts[2].trim();

                Map<String, String> resourceUsageMap = new HashMap<>();
                resourceUsageMap.put(DOCKER_RESOURCE_KEY_CPU, cpuUsage);
                resourceUsageMap.put(DOCKER_RESOURCE_KEY_MEMORY, memUsage);

                containerUsageMap.put(containerName, resourceUsageMap);
            }
        }
        return containerUsageMap;
    }

    private void cacheResourceUsage(Long userId, Map<String, Map<String, String>> resourceUsage) {
        String value = convertMetricMapToString(resourceUsage);
        if (!value.isBlank()) {
            redisService.storeValue(RESOURCE_KEY, userId.toString(), value, RESOURCE_EXP);
        }
    }

    private List<SshInfo> extractUniqueSshInfos(List<Project> projects) {
        return projects.stream()
                .map(Project::getSshInfo)
                .distinct()
                .toList();
    }

    private String convertMetricMapToString(Map<String, Map<String, String>> metricMap){
        try {
            return objectMapper.writeValueAsString(metricMap);
        } catch (JsonProcessingException e) {
            log.info("MetricMap 파싱 실패");
            return BLANK_STRING;
        }
    }

    private String buildContainerCommand(String commandType,String containerName) {
        return commandType + containerName;
    }

    private boolean isCommandSuccess(String response, String containerName) {
        return response != null && response.trim().equals(containerName);
    }

    private List<String> parseContainerList(String commandResponse) {
        return Arrays.stream(commandResponse.split("\n"))
                .map(String::trim)
                .filter(containerName -> !containerName.isBlank())
                .toList();
    }
}
