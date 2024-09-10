package com.example.llmn.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Service
public class DockerService {

    // 도커 컨테이너 종료
    public void stopContainerByName(String containerName) throws Exception {
        String command = "docker stop " + containerName;
        executeCommand(command);
    }

    // 도커 컨테이너 재시작
    public void restartContainerByName(String containerName) throws Exception {
        String command = "docker restart " + containerName;
        executeCommand(command);
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

        // 출력 결과에서 정확하게 컨테이너 이름이 일치하는지 확인
        return output.stream().anyMatch(name -> name.equals(containerName));
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
