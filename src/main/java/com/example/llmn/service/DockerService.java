package com.example.llmn.service;

import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.domain.Project;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.example.llmn.core.utils.JsonUtils.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DockerService {

    private final SSHService sshService;
    private final RedisService redisService;
    private final ProjectRepository projectRepository;

    private static final String RESOURCE_KEY = "resource";
    public static final String DOCKER_RESOURCE_KEY_CPU = "CPU";
    public static final String DOCKER_RESOURCE_KEY_MEMORY = "Memory";
    public static final String COMMAND_CONTAINER_STOP = "docker stop ";
    public static final String COMMAND_CONTAINER_RESTART = "docker restart ";
    public static final String COMMAND_CONTAINER_PS = "docker ps --format \"{{.Names}}\"";
    public static final String COMMAND_CONTAINER_STATS = "docker stats --no-stream --format \"{{.Name}}:{{.CPUPerc}}:{{.MemUsage}}\"";
    private static final Long RESOURCE_EXP = 10 * 60 * 1000L; // 10분

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

        return convertJsonToMap(cachedValue);
    }

    private boolean hasCachedValue(Map<String, Map<String, String>> cachedResourceUsage) {
        return !cachedResourceUsage.isEmpty();
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
        if (commandResponse == null || commandResponse.isBlank()) {
            return Collections.emptyMap();
        }

        // 각 줄을 ':'로 나누어 컨테이너 이름, CPU, 메모리 사용량을 추출
        return Arrays.stream(commandResponse.split("\n"))
                .map(this::parseLineToContainerUsage)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map.Entry<String, Map<String, String>> parseLineToContainerUsage(String line) {
        String[] parts = line.split(":");

        if (parts.length == 3) { // 유효한 라인만 처리
            String containerName = parts[0].trim();
            String cpuUsage = parts[1].trim();
            String memUsage = parts[2].trim();

            Map<String, String> resourceUsageMap = Map.of(
                    DOCKER_RESOURCE_KEY_CPU, cpuUsage,
                    DOCKER_RESOURCE_KEY_MEMORY, memUsage
            );

            return Map.entry(containerName, resourceUsageMap);
        }

        return null;
    }

    private void cacheResourceUsage(Long userId, Map<String, Map<String, String>> resourceUsage) {
        String value = convertMapToJson(resourceUsage);
        if (isNotEmpty(value)) {
            redisService.storeValue(RESOURCE_KEY, userId.toString(), value, RESOURCE_EXP);
        }
    }

    private List<SshInfo> extractUniqueSshInfos(List<Project> projects) {
        return projects.stream()
                .map(Project::getSshInfo)
                .distinct()
                .toList();
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
