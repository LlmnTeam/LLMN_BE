package com.example.llmn.domain.remote;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.remote.model.SshInfoDTO;
import com.example.llmn.domain.user.UserRepository;
import com.example.llmn.integration.minasshd.MinaSshdService;
import com.example.llmn.integration.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.llmn.common.constants.GlobalConstants.DELIMITER;
import static com.example.llmn.integration.redis.RedisConstants.REDIS_KEY_SSH;
import static com.example.llmn.integration.redis.RedisConstants.REDIS_EXP_SSH;

@Service
@RequiredArgsConstructor
@Slf4j
public class SSHService {

    private final RedisService redisService;
    private final UserRepository userRepository;
    private final SshInfoRepository sshInfoRepository;
    private final Map<Long, MinaSshdService> executorSession = new ConcurrentHashMap<>();

    public static final String UPTIME_COMMAND = "uptime";
    public static final String UPTIME_COMMAND_RESPONSE = "load average";

    @Transactional
    public void initCommend(Long userId){
        Long monitoringSshId = userRepository.findMonitoringSshId(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        MinaSshdService executor = getSshExecutor(monitoringSshId, true);
        executor.flushInitialMessage();
    }

    @Transactional
    public String executeCommandInShell(String command, boolean isFirstExecution, Long userId){
        Long monitoringSshId = userRepository.findMonitoringSshId(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        MinaSshdService executor = getSshExecutor(monitoringSshId, isFirstExecution);
        return executor.executeCommandInShell(command);
    }

    @Transactional
    public String executeCommandOnce(String command, Long sshInfoId) {
        MinaSshdService executor = getSshExecutor(sshInfoId, false);
        return executor.executeCommandOnce(command);
    }

    @Transactional
    public void stopCommend(Long userId){
        Long monitoringSshId = userRepository.findMonitoringSshId(userId).orElseThrow(
                () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
        );

        MinaSshdService executor = executorSession.get(monitoringSshId);
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

        MinaSshdService executor = getSshExecutor(monitoringSshId, false);
        executor.sendSigint();
    }

    @Scheduled(cron = "0 32 12 * * *")
    public void checkSshConnection(){
        List<SshInfo> sshInfos = sshInfoRepository.findAll();
        sshInfos.forEach(sshInfo -> sshInfo.updateIsWorking(checkConnectionValid(sshInfo.getId())));
    }

    public boolean checkConnectionValid(String remoteHost, String remoteName, String remoteKeyPath) {
        MinaSshdService executor = new MinaSshdService(remoteHost, remoteName, remoteKeyPath);
        String response = executor.executeCommandOnce(UPTIME_COMMAND);
        executor.close();

        return response.contains(UPTIME_COMMAND_RESPONSE);
    }

    public boolean checkConnectionValid(Long sshInfoId) {
        MinaSshdService executor = getSshExecutor(sshInfoId, false);
        String response = executor.executeCommandOnce(UPTIME_COMMAND);

        return response.contains(UPTIME_COMMAND_RESPONSE);
    }

    public synchronized MinaSshdService getSshExecutor(Long sshInfoId, boolean isFirstExecution) {
        // 세션이 열려있으면 그대로 반환
        MinaSshdService executor = executorSession.get(sshInfoId);
        if(!isFirstExecution && executor != null && executor.isConnected()){
            return executor;
        }

        // SSH 정보 가져오기
        SshInfoDTO sshInfoDTO = getSshInfo(sshInfoId);
        if (sshInfoDTO == null) return null;

        // 새로운 세션 생성 후 저장
        try {
            MinaSshdService newExecutor = new MinaSshdService(sshInfoDTO.remoteHost(), sshInfoDTO.remoteName(), sshInfoDTO.remoteKeyPath());
            executorSession.put(sshInfoId, newExecutor);
            return newExecutor;
        } catch (Exception e) {
            return null;
        }
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
        String sshInfo = redisService.getValueInString(REDIS_KEY_SSH, sshInfoId.toString());
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
        redisService.storeValue(REDIS_KEY_SSH, sshInfoId.toString(), combinedInfo, REDIS_EXP_SSH);
    }
}