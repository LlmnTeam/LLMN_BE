package com.example.llmn.service;

import com.example.llmn.controller.DTO.SshInfoDTO;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.core.utils.SSHCommandExecutor;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.repository.SshInfoRepository;
import com.example.llmn.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class SSHService {

    public static final String UPTIME_COMMAND = "uptime";
    public static final String UPTIME_COMMAND_RESPONSE = "load average";
    private final RedisService redisService;
    private final UserRepository userRepository;
    private final SshInfoRepository sshInfoRepository;
    private final Map<Long, SSHCommandExecutor> executorSession = new ConcurrentHashMap<>();
    private static final String REDIS_SSH_KEY = "SSH";
    public static final Long REDIS_SSH_KEY_EXP = 60L * 60 * 24 * 30; // 30일
    private static final String DELIMITER = "-";

    @Transactional
    public void initCommend(Long userId){
        Long monitoringSshId = userRepository.findMonitoringSshId(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        SSHCommandExecutor executor = getSshExecutor(monitoringSshId, true);
        executor.flushInitialMessage();
    }

    @Transactional
    public String executeCommandInShell(String command, boolean isFirstExecution, Long userId){
        Long monitoringSshId = userRepository.findMonitoringSshId(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        SSHCommandExecutor executor = getSshExecutor(monitoringSshId, isFirstExecution);
        return executor.executeCommandInShell(command);
    }

    @Transactional
    public String executeCommandOnce(String command, Long sshInfoId) {
        SSHCommandExecutor executor = getSshExecutor(sshInfoId, false);
        return executor.executeCommandOnce(command);
    }

    @Transactional
    public void stopCommend(Long userId){
        Long monitoringSshId = userRepository.findMonitoringSshId(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        SSHCommandExecutor executor = executorSession.get(monitoringSshId);
        if (executor != null) {
            executor.close();
            executorSession.remove(monitoringSshId);
        }
    }

    @Transactional
    public void executeSigInt(Long userId){
        Long monitoringSshId = userRepository.findMonitoringSshId(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        SSHCommandExecutor executor = getSshExecutor(monitoringSshId, false);
        executor.sendSigint();
    }

    @Scheduled(cron = "0 32 12 * * *")
    public void checkSshConnection(){
        List<SshInfo> sshInfos = sshInfoRepository.findAll();
        sshInfos.forEach(sshInfo -> sshInfo.updateIsWorking(checkConnectionValid(sshInfo.getId())));
    }

    public boolean checkConnectionValid(String remoteHost, String remoteName, String remoteKeyPath) {
        SSHCommandExecutor executor = new SSHCommandExecutor(remoteHost, remoteName, remoteKeyPath);
        String response = executor.executeCommandOnce(UPTIME_COMMAND);
        executor.close();

        return response.contains(UPTIME_COMMAND_RESPONSE);
    }

    public boolean checkConnectionValid(Long sshInfoId) {
        SSHCommandExecutor executor = getSshExecutor(sshInfoId, false);
        String response = executor.executeCommandOnce(UPTIME_COMMAND);

        return response.contains(UPTIME_COMMAND_RESPONSE);
    }

    public SSHCommandExecutor getSshExecutor(Long sshInfoId, boolean isFirstExecution) {
        if (!isFirstExecution) {
            return getConnectedExecutor(sshInfoId)
                    .orElseGet(() -> initializeExecutor(sshInfoId));
        }

        return initializeExecutor(sshInfoId);
    }

    private Optional<SSHCommandExecutor> getConnectedExecutor(Long sshInfoId) {
        SSHCommandExecutor executor = executorSession.get(sshInfoId);
        if (executor.isConnected()) {
            return Optional.of(executor);
        }

        return Optional.empty();
    }

    private SSHCommandExecutor initializeExecutor(Long sshInfoId) {
        return executorSession.computeIfAbsent(sshInfoId, id -> {
            SshInfoDTO sshInfoDTO = getSshInfo(id);
            return createExecutor(sshInfoDTO);
        });
    }

    private SSHCommandExecutor createExecutor(SshInfoDTO sshInfoDTO) {
        return new SSHCommandExecutor(
                sshInfoDTO.remoteHost(),
                sshInfoDTO.remoteName(),
                sshInfoDTO.remoteKeyPath()
        );
    }

    private SshInfoDTO getSshInfo(Long sshInfoId) {
        return retrieveCachedSshInfo(sshInfoId)
                .orElseGet(() -> fetchSshInfoFromDb(sshInfoId)
                        .map(sshInfo -> {
                            cacheSshInfo(sshInfoId, sshInfo);
                            return new SshInfoDTO(sshInfo.getRemoteHost(), sshInfo.getRemoteName(), sshInfo.getRemoteKeyPath());
                        })
                        .orElseThrow(() -> new CustomException(ExceptionCode.SSH_NOT_FOUND)));
    }

    private Optional<SshInfoDTO> retrieveCachedSshInfo(Long sshInfoId) {
        String sshInfo = redisService.getValueInString(REDIS_SSH_KEY, sshInfoId.toString());
        if (sshInfo == null) {
            return Optional.empty();
        }

        return parseSshInfo(sshInfo);
    }

    private Optional<SshInfoDTO> parseSshInfo(String sshInfoStr) {
        String[] parts = sshInfoStr.split(DELIMITER);
        if (parts.length != 3) {
            return Optional.empty();
        }

        return Optional.of(new SshInfoDTO(parts[0], parts[1], parts[2]));
    }

    private Optional<SshInfo> fetchSshInfoFromDb(Long sshInfoId) {
        return sshInfoRepository.findById(sshInfoId);
    }

    private void cacheSshInfo(Long sshInfoId, SshInfo sshInfo) {
        String combinedInfo = String.join(DELIMITER, sshInfo.getRemoteHost(), sshInfo.getRemoteName(), sshInfo.getRemoteKeyPath());
        redisService.storeValue(REDIS_SSH_KEY, sshInfoId.toString(), combinedInfo, REDIS_SSH_KEY_EXP);
    }
}