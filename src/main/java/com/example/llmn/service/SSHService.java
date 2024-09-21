package com.example.llmn.service;

import com.example.llmn.core.utils.SSHCommandExecutor;
import org.springframework.stereotype.Service;

@Service
public class SSHService {
    private SSHCommandExecutor executor;
    private static final String HOST = "54.180.202.202";
    private static final String USER_NAME = "ubuntu";
    private static final String PRIVATE_KEY_PATH = "file:///Users/lhh/LLMN/ssh/Llmn.pem";
    
    // 명령어 실행
    public String executeCommandInShell(String command) throws Exception {
        connectIfNecessary();
        return executor.executeCommandInShell(command);
    }

    public String executeCommandOnce(String command) throws Exception {
        connectIfNecessary();
        return executor.executeCommandOnce(command);
    }

    // SSH 세션 종료
    public void closeSession() throws Exception {
        if (executor != null) {
            executor.close();
        }
    }

    private synchronized void connectIfNecessary() throws Exception {
        // SSHCommandExecutor가 없거나, 세션이 연결되어 있지 않다면 세션을 생성
        if (executor == null || !executor.isConnected()) {
            this.executor = new SSHCommandExecutor(HOST, USER_NAME, PRIVATE_KEY_PATH);
        }
    }
}

