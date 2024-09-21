package com.example.llmn.service;

import com.example.llmn.core.utils.SSHCommandExecutor;
import org.springframework.stereotype.Service;

@Service
public class SSHService {
    private final SSHCommandExecutor executor;
    private static final String HOST = "54.180.202.202";
    private static final String USER_NAME = "ubuntu";
    private static final String PRIVATE_KEY_PATH = "file:///Users/lhh/LLMN/ssh/Llmn.pem";

    // 한 번만 SSH 세션을 생성
    public SSHService() throws Exception {
        this.executor = new SSHCommandExecutor(HOST, USER_NAME, PRIVATE_KEY_PATH);
    }

    // 명령어 실행
    public String executeCommand(String command) throws Exception {
        return executor.executeCommand(command);
    }

    // SSH 세션 종료
    public void closeSession() throws Exception {
        if (executor != null) {
            executor.close();
        }
    }
}

