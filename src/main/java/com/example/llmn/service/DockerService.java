package com.example.llmn.service;

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
    public boolean stopContainerByName(String containerName, Long userId) throws Exception {
        String command = COMMAND_DOCKER_STOP + containerName;
        String commandResponse = sshService.executeCommandOnce(command, userId);

        return commandResponse.trim().equals(containerName);  // 성공 여부 true/false로 리턴
    }

    // 도커 컨테이너 재시작
    public boolean restartContainerByName(String containerName, Long userId) throws Exception {
        String command = COMMAND_DOCKER_RESTART + containerName;
        String commandResponse = sshService.executeCommandOnce(command, userId);

        return commandResponse.trim().equals(containerName);
    }

    // 실행중인 도커 컨테이너 목록 조회
    public List<String> findRunningContainerList(Long userId) throws Exception {
        String commandResponse = sshService.executeCommandOnce(COMMAND_DOCKER_PS, userId);

        // 응답을 줄 단위로 나눠서 리스트로 변환
        List<String> containerNames = Arrays.asList(commandResponse.split("\n"));

        // 각 항목의 앞뒤 공백을 제거
        containerNames = containerNames.stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toList());

        return containerNames;
    }

    // 특정 컨테이너의 실행 여부 확인
    public boolean isContainerRunning(String containerName, Long userId) throws Exception {
        List<String> containerList = findRunningContainerList(userId);

        return containerList.stream()
                .anyMatch(name -> name.equals(containerName));
    }

    // 컨테이너의 사용 리소스 조회
    public Map<String, Map<String, String>> findContainersResourceUsage(Long userId, boolean isUsingCache) throws Exception {
        // 캐시를 사용하면 => 레디스에서 캐시된 값을 먼저 조회
        Map<String, Map<String, String>> cachedUsage = isUsingCache ? getCachedResourceUsage(userId) : null;
        if (cachedUsage != null) {
            return cachedUsage;
        }

        // 캐시를 사용하지 않거나 캐시된 값이 없음 => 조회해서 사용
        String commandResponse = sshService.executeCommandOnce(COMMAND_DOCKER_STATS, userId);

        // 결과를 줄 단위로 나눔
        String[] lines = commandResponse.split("\n");

        // 컨테이너 이름을 키로 하고, CPU와 메모리 사용량을 담은 맵을 값으로 하는 바깥쪽 맵 생성
        Map<String, Map<String, String>> containerUsageMap = new HashMap<>();

        // 각 줄을 ':'로 나누어 컨테이너 이름, CPU, 메모리 사용량을 추출
        for (String line : lines) {
            String[] parts = line.split(":");

            // 예상되는 3개의 요소가 모두 있는지 확인
            if (parts.length == 3) {
                String containerName = parts[0].trim();
                String cpuUsage = parts[1].trim();
                String memUsage = parts[2].trim();

                // 내부 맵 생성 후 CPU와 메모리 사용량 추가
                Map<String, String> resourceUsage = new HashMap<>();
                resourceUsage.put(DOCKER_RESOURCE_KEY_CPU, cpuUsage);
                resourceUsage.put(DOCKER_RESOURCE_KEY_MEMORY, memUsage);

                containerUsageMap.put(containerName, resourceUsage);
            }
        }

        // 유효 시간은 10분
        redisService.storeValue(RESOURCE_KEY, userId.toString(), objectMapper.writeValueAsString(containerUsageMap), RESOURCE_EXP);

        return containerUsageMap;
    }

    // 명령어 실행 함수
    private void executeCommand(String command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("bash", "-c", command);
        Process process = processBuilder.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("명령어 실행 실패: " + exitCode);
        }
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
}
