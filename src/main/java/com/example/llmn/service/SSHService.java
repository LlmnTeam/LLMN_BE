package com.example.llmn.service;

import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.core.utils.SSHCommandExecutor;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SSHService {
    private SSHCommandExecutor executor;
    private final RedisService redisService;
    private final UserRepository userRepository;
    
    private static final String REDIS_SSH_KEY = "SSH";
    public static final Long REDIS_SSH_KEY_EXP = 60L * 60 * 24 * 180; // 180일
    private static final String DELIMITER = ":";
    
    // 명령어 실행
    public String executeCommandInShell(String command, Long userId) throws Exception {
        connectIfNecessary(userId);
        return executor.executeCommandInShell(command);
    }

    public String executeCommandOnce(String command, Long userId) throws Exception {
        connectIfNecessary(userId);
        return executor.executeCommandOnce(command);
    }

    // SSH 세션 종료
    public void closeSession() throws Exception {
        if (executor != null) {
            executor.close();
        }
    }

    private synchronized void connectIfNecessary(Long userId) throws Exception {
        // Redis 또는 DB에서 SSH 정보를 가져옴
        SshInfo sshInfo = getSshInfo(userId);

        // SSHCommandExecutor가 없거나, 세션이 연결되어 있지 않다면 세션을 생성
        if (executor == null || !executor.isConnected()) {
            this.executor = new SSHCommandExecutor(sshInfo.getRemoteHost(), sshInfo.getRemoteName(), sshInfo.getRemoteKeyPath());
        }
    }

    private SshInfo getSshInfo(Long userId) {
        String sshInfoStr = redisService.getDataInStr(REDIS_SSH_KEY, userId.toString());

        // 레디스에 데이터가 없으면 DB에서 가져옴
        if (sshInfoStr == null) {
            SshInfo sshInfoInDB = userRepository.findSshInfoById(userId).orElseThrow(
                    () -> new CustomException(ExceptionCode.USER_NOT_FOUND)
            );

            String combinedInfo = String.join(DELIMITER, sshInfoInDB.getRemoteHost(), sshInfoInDB.getRemoteName(), sshInfoInDB.getRemoteKeyPath());
            redisService.storeValue(REDIS_SSH_KEY, userId.toString(), combinedInfo, REDIS_SSH_KEY_EXP);

            return sshInfoInDB;
        } else {
            return parseSshInfo(sshInfoStr);
        }
    }

    // sshInfoStr을 SsshInfo 객체로 변환
    private SshInfo parseSshInfo(String sshInfoStr) {
        String[] parts = sshInfoStr.split(DELIMITER);

        if (parts.length != 3) {
            throw new IllegalArgumentException("잘못된 SSH 정보 형식입니다.");
        }

        return new SshInfo(parts[0], parts[1], parts[2]);
    }
}

