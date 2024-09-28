package com.example.llmn.service;

import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.domain.Project;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.repository.ProjectRepository;
import com.example.llmn.repository.SshInfoRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DockerService {

    private final SSHService sshService;
    private final RedisService redisService;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    private static final String RESOURCE_KEY = "resource";
    public static final String DOCKER_RESOURCE_KEY_CPU = "CPU";
    public static final String DOCKER_RESOURCE_KEY_MEMORY = "Memory";
    public static final String COMMAND_DOCKER_STOP = "docker stop ";
    public static final String COMMAND_DOCKER_RESTART = "docker restart ";
    public static final String COMMAND_DOCKER_PS = "docker ps --format \"{{.Names}}\"";
    public static final String COMMAND_DOCKER_STATS = "docker stats --no-stream --format \"{{.Name}}:{{.CPUPerc}}:{{.MemUsage}}\"";
    private static final Long RESOURCE_EXP = 10 * 60 * 1000L; // 10분

    // 도커 컨테이너 종료
    public boolean stopContainerByName(String containerName, Long projectId) throws Exception {
        Long sshInfoId = projectRepository.findSshInfoId(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND)
        );

        String command = COMMAND_DOCKER_STOP + containerName;
        String commandResponse = sshService.executeCommandOnce(command, sshInfoId);

        return commandResponse.trim().equals(containerName);  // 성공 여부 true/false로 리턴
    }

    // 도커 컨테이너 재시작
    public boolean restartContainerByName(String containerName, Long projectId) throws Exception {
        Long sshInfoId = projectRepository.findSshInfoId(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND)
        );

        String command = COMMAND_DOCKER_RESTART + containerName;
        String commandResponse = sshService.executeCommandOnce(command, sshInfoId);

        return commandResponse.trim().equals(containerName); // 성공 여부 true/false로 리턴
    }

    // 실행중인 도커 컨테이너 목록 조회
    public List<String> findRunningContainerList(Long projectId) throws Exception {
        Long sshInfoId = projectRepository.findSshInfoId(projectId).orElseThrow(
                () -> new CustomException(ExceptionCode.PROJECT_NOT_FOUND)
        );

        String commandResponse = sshService.executeCommandOnce(COMMAND_DOCKER_PS, sshInfoId);

        // 줄 단위로 나눠서 리스트로 변환
        List<String> containerNames = Arrays.asList(commandResponse.split("\n"));

        // 각 항목의 앞뒤 공백을 제거
        containerNames = containerNames.stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toList());

        return containerNames;
    }

    // 특정 컨테이너의 실행 여부 확인
    public boolean isContainerRunning(String containerName, Long projectId) throws Exception {
        List<String> containerList = findRunningContainerList(projectId);

        return containerList.stream()
                .anyMatch(name -> name.equals(containerName));
    }

    // 컨테이너의 사용 리소스 조회
    public Map<String, Map<String, String>> findContainersResourceUsage(List<Project> projects, Long userId, boolean isUsingCache) throws Exception {
        // 1. 캐시를 사용하면 => 레디스에서 캐시된 값을 먼저 조회
        Map<String, Map<String, String>> cachedUsage = isUsingCache ? getCachedResourceUsage(userId) : null;
        if (cachedUsage != null) {
            return cachedUsage;
        }

        // 2. 캐시를 사용하지 않거나 캐시된 값이 없음 => 명령어를 통해 조회
        List<SshInfo> sshInfos = projects.stream()
                .map(Project::getSshInfo)
                .distinct()
                .toList();

        Map<String, Map<String, String>> containerUsageMap = new HashMap<>();
        for(SshInfo sshInfo : sshInfos){
            String commandResponse = sshService.executeCommandOnce(COMMAND_DOCKER_STATS, sshInfo.getId());
            Map<String, Map<String, String>> parsedMap = parseCommandResponse(commandResponse);

            containerUsageMap.putAll(parsedMap);
        }

        // 유효 시간은 10분
        redisService.storeValue(RESOURCE_KEY, userId.toString(), objectMapper.writeValueAsString(containerUsageMap), RESOURCE_EXP);

        return containerUsageMap;
    }

    private Map<String, Map<String, String>> getCachedResourceUsage(Long userId) {
        String cachedValue = redisService.getDataInStr(RESOURCE_KEY, userId.toString());

        // 캐시된 값이 없으면 null 반환
        if (cachedValue == null) {
            return null;
        }

        return convertStringToMetricMap(cachedValue);
    }

    private Map<String, Map<String, String>> convertStringToMetricMap(String cachedValue) {
        try {
            return objectMapper.readValue(cachedValue, new TypeReference<Map<String, Map<String, String>>>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
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
}
