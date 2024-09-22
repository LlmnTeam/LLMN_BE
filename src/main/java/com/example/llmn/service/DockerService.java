package com.example.llmn.service;

import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.repository.UserRepository;
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
    public boolean stopContainerByName(String containerName) throws Exception {
        String command = "docker stop " + containerName;
        String commandResponse = sshService.executeCommandOnce(command);

        return commandResponse.trim().equals(containerName);  // 성공 여부 true/false로 리턴
    }

    // 도커 컨테이너 재시작
    public boolean restartContainerByName(String containerName) throws Exception {
        String command = "docker restart " + containerName;
        String commandResponse = sshService.executeCommandOnce(command);

        return commandResponse.trim().equals(containerName);
    }

    // 실행중인 도커 컨테이너 목록 조회
    public List<String> findRunningContainerNameList() throws Exception {
        String command = "docker ps --format \"{{.Names}}\"";
        String commandResponse = sshService.executeCommandOnce(command);

        // 응답을 줄 단위로 나눠서 리스트로 변환
        List<String> containerNames = Arrays.asList(commandResponse.split("\n"));

        // 각 항목의 앞뒤 공백을 제거
        containerNames = containerNames.stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toList());

        return containerNames;
    }

    // 특정 컨테이너 이름으로 실행 여부 확인
    public boolean isContainerRunning(String containerName) throws Exception {
        String command = "docker ps --filter \"name=" + containerName + "\" --format \"{{.Names}}\"";
        List<String> output = executeCommandAndReturnOutput(command);
        return output.stream().anyMatch(name -> name.equals(containerName));
    }

    // 컨테이너의 사용 리소스 조회
    public Map<String, Map<String, String>> findAllContainersResourceUsage() throws Exception {
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
