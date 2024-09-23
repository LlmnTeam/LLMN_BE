package com.example.llmn.service;

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

    // 도커 컨테이너 종료
    public boolean stopContainerByName(String containerName, Long userId) throws Exception {
        String command = "docker stop " + containerName;
        String commandResponse = sshService.executeCommandOnce(command, userId);

        return commandResponse.trim().equals(containerName);  // 성공 여부 true/false로 리턴
    }

    // 도커 컨테이너 재시작
    public boolean restartContainerByName(String containerName, Long userId) throws Exception {
        String command = "docker restart " + containerName;
        String commandResponse = sshService.executeCommandOnce(command, userId);

        return commandResponse.trim().equals(containerName);
    }

    // 실행중인 도커 컨테이너 목록 조회
    public List<String> findRunningContainerList(Long userId) throws Exception {
        String command = "docker ps --format \"{{.Names}}\"";
        String commandResponse = sshService.executeCommandOnce(command, userId);

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
    public Map<String, Map<String, String>> findContainersResourceUsage(Long userId) throws Exception {
        String command = "docker stats --no-stream --format \"{{.Name}}:{{.CPUPerc}}:{{.MemUsage}}\"";
        String commandResponse = sshService.executeCommandOnce(command, userId);

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
                resourceUsage.put("CPU", cpuUsage);
                resourceUsage.put("Memory", memUsage);

                containerUsageMap.put(containerName, resourceUsage);
            }
        }

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
}
