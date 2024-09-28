package com.example.llmn.service;

import com.example.llmn.controller.DTO.SshInfoDTO;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.core.utils.SSHCommandExecutor;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.domain.User;
import com.example.llmn.repository.SshInfoRepository;
import com.example.llmn.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SSHService {
    private SSHCommandExecutor executor;
    private final RedisService redisService;
    private final UserRepository userRepository;
    private final SshInfoRepository sshInfoRepository;

    private static final String REDIS_SSH_KEY = "SSH";
    public static final Long REDIS_SSH_KEY_EXP = 60L * 60 * 24 * 180; // 180일
    private static final String DELIMITER = "-";
    
    // 명령어 실행
    public String executeCommandInShell(String command, Long sshInfoId) throws Exception {
        connectIfNecessary(sshInfoId);
        return executor.executeCommandInShell(command);
    }

    public String executeCommandOnce(String command, Long sshInfoId) throws Exception {
        connectIfNecessary(sshInfoId);
        return executor.executeCommandOnce(command);
    }

    // SSH 세션 종료
    public void closeSession() throws Exception {
        if (executor != null) {
            executor.close();
        }
    }

    private synchronized void connectIfNecessary(Long sshInfoId) throws Exception {
        // Redis 또는 DB에서 SSH 정보를 가져옴
        SshInfoDTO sshInfoDTO = getSshInfo(sshInfoId);

        // SSHCommandExecutor가 없거나, 세션이 연결되어 있지 않다면 세션을 생성
        if (executor == null || !executor.isConnected()) {
            this.executor = new SSHCommandExecutor(sshInfoDTO.remoteHost(), sshInfoDTO.remoteName(), sshInfoDTO.remoteKeyPath());
        }
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

