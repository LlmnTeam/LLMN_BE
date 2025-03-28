package com.example.llmn.domain.remote;

import com.example.llmn.integration.minasshd.MinaSshdService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.example.llmn.integration.minasshd.MinaSshdConstants.UPTIME_COMMAND;
import static com.example.llmn.integration.minasshd.MinaSshdConstants.UPTIME_COMMAND_RESPONSE;

@Service
@RequiredArgsConstructor
@Slf4j
public class SSHScheduler {

    private final SshInfoRepository sshInfoRepository;
    private final SSHService sshService;

    @Scheduled(cron = "0 32 12 * * *")
    public void checkSshConnection(){
        List<SshInfo> sshInfos = sshInfoRepository.findAll();
        sshInfos.forEach(sshInfo -> {
            boolean isWorking = checkConnectionValid(sshInfo.getId());
            sshInfo.updateIsWorking(isWorking);
        });
    }

    private boolean checkConnectionValid(Long sshInfoId) {
        MinaSshdService executor = sshService.getSshExecutor(sshInfoId, false);
        String response = executor.executeCommandOnce(UPTIME_COMMAND);
        return response.contains(UPTIME_COMMAND_RESPONSE);
    }
}
