package com.example.llmn.domain.docker;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.project.Project;
import com.example.llmn.domain.remote.ServerInstance;
import com.example.llmn.domain.project.ProjectRepository;
import com.example.llmn.domain.remote.SecureShellManager;
import com.example.llmn.integration.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.example.llmn.common.utils.JsonUtils.*;
import static com.example.llmn.domain.docker.DockerConstants.*;
import static com.example.llmn.integration.redis.RedisConstants.REDIS_KEY_RESOURCE;
import static com.example.llmn.integration.redis.RedisConstants.REDIS_EXPIRY_RESOURCE_MS;

@Service
@RequiredArgsConstructor
@Slf4j
public class DockerService {

    private final SecureShellManager secureShellManager;
    private final RedisService redisService;
    private final ProjectRepository projectRepository;

    public boolean stopContainer(String containerName, Long projectId) {
        Long serverInstanceId = projectRepository.findServerInstanceId(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND));

        String command = buildContainerCommand(COMMAND_CONTAINER_STOP, containerName);
        String commandResponse = secureShellManager.executeOneTimeCommand(command, serverInstanceId);

        return isCommandSuccess(containerName, commandResponse);
    }

    public boolean restartContainer(String containerName, Long projectId) {
        Long serverInstanceId = projectRepository.findServerInstanceId(projectId)
                .orElseThrow(() -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND));

        String command = buildContainerCommand(COMMAND_CONTAINER_RESTART, containerName);
        String commandResponse = secureShellManager.executeOneTimeCommand(command, serverInstanceId);

        return isCommandSuccess(containerName, commandResponse);
    }

    public List<String> findRunningContainerList(Long serverInstanceId) {
        String commandResponse = secureShellManager.executeOneTimeCommand(COMMAND_CONTAINER_PS, serverInstanceId);
        return parseContainerList(commandResponse);
    }

    public boolean isContainerRunning(String containerName, Long serverInstanceId) {
        return findRunningContainerList(serverInstanceId).stream()
                .anyMatch(name -> name.equals(containerName));
    }

    public Map<String, Map<String, String>> findContainersResourceUsage(List<Project> projects, Long userId, boolean isUsingCache) {
        Map<String, Map<String, String>> cachedResourceUsage = retrieveCachedResourceUsage(isUsingCache, userId);
        if (hasCachedValue(cachedResourceUsage))
            return cachedResourceUsage;

        List<ServerInstance> serverInstances = extractUniqueServerInstances(projects);
        Map<String, Map<String, String>> resourceUsage = fetchResourceUsageFromSsh(serverInstances);

        cacheResourceUsage(userId, resourceUsage);

        return resourceUsage;
    }

    private Map<String, Map<String, String>> retrieveCachedResourceUsage(boolean isUsingCache, Long userId) {
        if (!isUsingCache) {
            return Collections.emptyMap();
        }

        String cachedValue = redisService.getValueInString(REDIS_KEY_RESOURCE, userId.toString());

        if (cachedValue == null) {
            return Collections.emptyMap();
        }

        return convertJsonToMap(cachedValue);
    }

    private boolean hasCachedValue(Map<String, Map<String, String>> cachedResourceUsage) {
        return !cachedResourceUsage.isEmpty();
    }

    private Map<String, Map<String, String>> fetchResourceUsageFromSsh(List<ServerInstance> serverInstances) {
        Map<String, Map<String, String>> resourceUsageMap = new HashMap<>();

        for (ServerInstance serverInstance : serverInstances) {
            String commandResponse = secureShellManager.executeOneTimeCommand(COMMAND_CONTAINER_STATS, serverInstance.getId());
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
            redisService.storeValue(REDIS_KEY_RESOURCE, userId.toString(), value, REDIS_EXPIRY_RESOURCE_MS);
        }
    }

    private List<ServerInstance> extractUniqueServerInstances(List<Project> projects) {
        return projects.stream()
                .map(Project::getServerInstance)
                .distinct()
                .toList();
    }

    private String buildContainerCommand(String commandType, String containerName) {
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
