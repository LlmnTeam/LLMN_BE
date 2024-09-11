package com.example.llmn.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DockerService {

    // 도커 컨테이너 종료
    public void stopContainerByName(String containerName) throws Exception {
        if (isContainerRunning(containerName)) {
            String command = "docker stop " + containerName;
            executeCommand(command);
        }
    }

    // 도커 컨테이너 재시작
    public void restartContainerByName(String containerName) throws Exception {
        if (isContainerRunning(containerName)) {
            String command = "docker restart " + containerName;
            executeCommand(command);
        }
    }

    // 도커 컨테이너 이름 목록
    public List<String> findRunningContainerNameList() throws Exception {
        String command = "docker ps --format \"{{.Names}}\"";
        return executeCommandAndReturnOutput(command);
    }

    // 특정 컨테이너 이름으로 실행 여부 확인
    public boolean isContainerRunning(String containerName) throws Exception {
        String command = "docker ps --filter \"name=" + containerName + "\" --format \"{{.Names}}\"";
        List<String> output = executeCommandAndReturnOutput(command);
        return output.stream().anyMatch(name -> name.equals(containerName));
    }

    // 컨테이너의 사용 리소스 조회
    public Map<String, Map<String, String>> getAllContainersResourceUsage() throws Exception {
        // docker stats 명령어로 모든 실행 중인 컨테이너의 CPU, 메모리 사용량 가져오기
        String command = "docker stats --no-stream --format \"{{.Name}}:{{.CPUPerc}}:{{.MemUsage}}\"";
        List<String> outputLines = executeCommandAndReturnOutput(command);

        // 컨테이너 이름을 키로 하고, CPU와 메모리 사용량을 값으로 하는 맵 생성
        Map<String, Map<String, String>> containersResourceUsage = new HashMap<>();
        for (String line : outputLines) {
            String[] usageStats = line.split(":");
            String containerName = usageStats[0].trim();
            String cpuUsage = usageStats[1].trim();
            String memoryUsage = usageStats[2].split(" / ")[0].trim();  // 메모리 사용량에서 첫 부분만 추출

            // 컨테이너 이름을 키로 하여 맵에 저장
            Map<String, String> resourceUsage = new HashMap<>();
            resourceUsage.put("CPU", cpuUsage);
            resourceUsage.put("Memory", memoryUsage);

            containersResourceUsage.put(containerName, resourceUsage);
        }

        return containersResourceUsage;
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
            throw new RuntimeException("Command execution failed with exit code: " + exitCode);
        }
    }

    // 명령어 실행 후 결과 반환 함수
    private List<String> executeCommandAndReturnOutput(String command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("bash", "-c", command);
        Process process = processBuilder.start();

        List<String> output = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            output.add(line);  // 각 라인은 컨테이너 이름이 됨
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command execution failed with exit code: " + exitCode);
        }

        return output;
    }
}
