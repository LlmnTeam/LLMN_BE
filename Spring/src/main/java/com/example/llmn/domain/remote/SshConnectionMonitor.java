package com.example.llmn.domain.remote;

import com.example.llmn.integration.minasshd.SecureShellClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.example.llmn.integration.minasshd.MinaSshdConstants.CONNECTION_TEST_COMMAND;
import static com.example.llmn.integration.minasshd.MinaSshdConstants.CONNECTION_TEST_SUCCESS_MARKER;

@Service
@RequiredArgsConstructor
@Slf4j
public class SshConnectionMonitor {

    private final SshInfoRepository sshInfoRepository;
    private final SecureShellManager secureShellManager;

    @Scheduled(cron = "0 32 12 * * *")
    public void checkSshConnection(){
        List<SshInfo> sshInfos = sshInfoRepository.findAll();
        sshInfos.forEach(sshInfo -> {
            boolean isWorking = checkConnectionValid(sshInfo.getId(), sshInfo.getUserId());
            sshInfo.updateIsWorking(isWorking);
        });
    }

    private boolean checkConnectionValid(Long sshInfoId, Long userId) {
        SecureShellClient executor = secureShellManager.getOrCreateSshClient(sshInfoId, false, userId);
        String response = executor.runSingleCommand(CONNECTION_TEST_COMMAND);
        return response.contains(CONNECTION_TEST_SUCCESS_MARKER);
    }
}
