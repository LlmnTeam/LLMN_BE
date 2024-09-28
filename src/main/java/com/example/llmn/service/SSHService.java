package com.example.llmn.service;

import com.example.llmn.controller.DTO.SshInfoDTO;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.core.utils.SSHCommandExecutor;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.repository.SshInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SSHService {

    private final RedisService redisService;
    private final SshInfoRepository sshInfoRepository;

    // SSH Executor(세션)을 저장하고 있는 맵
    private final Map<Long, SSHCommandExecutor> executorMap = new ConcurrentHashMap<>();
    private static final String REDIS_SSH_KEY = "SSH";
    public static final Long REDIS_SSH_KEY_EXP = 60L * 60 * 24 * 30; // 30일
    private static final String DELIMITER = "-";
    
    // 명령어 실행
    public String executeCommandInShell(String command, Long sshInfoId) throws Exception {
        SSHCommandExecutor executor = getSshExecutor(sshInfoId);
        return executor.executeCommandInShell(command);
    }

    public String executeCommandOnce(String command, Long sshInfoId) throws Exception {
        SSHCommandExecutor executor = getSshExecutor(sshInfoId);
        return executor.executeCommandOnce(command);
    }

    // SSH 세션 종료
    public void closeSession(Long sshInfoId) {
        SSHCommandExecutor executor = executorMap.get(sshInfoId);

        if (executor != null) {
            executor.close();
        }
    }

    private synchronized SSHCommandExecutor getSshExecutor(Long sshInfoId) throws Exception {
        SSHCommandExecutor executor = executorMap.get(sshInfoId);

        // executor가 없거나, 세션이 끊어졌다면 executor 새로 생성
        if (executor == null || !executor.isConnected()) {
            SshInfoDTO sshInfoDTO = getSshInfo(sshInfoId);
            executor = new SSHCommandExecutor(sshInfoDTO.remoteHost(), sshInfoDTO.remoteName(), sshInfoDTO.remoteKeyPath());

            executorMap.put(sshInfoId, executor);
        }

        return executor;
    }

    private SshInfoDTO getSshInfo(Long sshInfoId) {
        String sshInfoStr = redisService.getDataInStr(REDIS_SSH_KEY, sshInfoId.toString());

        // 레디스에 캐시된 값이 없으면 DB에서 가져옴
        if (sshInfoStr == null) {
            SshInfo sshInfoInDB = sshInfoRepository.findById(sshInfoId).orElseThrow(
                    () -> new CustomException(ExceptionCode.SSH_NOT_FOUND)
            );

            // remoteHost, remoteName, keyPath를 하나의 문자로 합쳐서 저장
            String combinedInfo = String.join(DELIMITER, sshInfoInDB.getRemoteHost(), sshInfoInDB.getRemoteName(), sshInfoInDB.getRemoteKeyPath());
            redisService.storeValue(REDIS_SSH_KEY, sshInfoId.toString(), combinedInfo, REDIS_SSH_KEY_EXP);

            return new SshInfoDTO(sshInfoInDB.getRemoteHost(), sshInfoInDB.getRemoteName(), sshInfoInDB.getRemoteKeyPath());
        } else {
            return parseSshInfo(sshInfoStr);
        }
    }

    // 문자인 sshInfoStr을 SsshInfo 객체로 변환
    private SshInfoDTO parseSshInfo(String sshInfoStr) {
        String[] parts = sshInfoStr.split(DELIMITER);

        if (parts.length != 3) {
            throw new IllegalArgumentException("잘못된 SSH 정보 형식입니다.");
        }

        return new SshInfoDTO(parts[0], parts[1], parts[2]);
    }
}

