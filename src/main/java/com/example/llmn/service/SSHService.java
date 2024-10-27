package com.example.llmn.service;

import com.example.llmn.controller.DTO.SshInfoDTO;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.core.utils.SSHCommandExecutor;
import com.example.llmn.domain.SshInfo;
import com.example.llmn.repository.SshInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class SSHService {

    private final RedisService redisService;
    private final SshInfoRepository sshInfoRepository;
    private final Map<Long, SSHCommandExecutor> executorSession = new ConcurrentHashMap<>();
    private static final String REDIS_SSH_KEY = "SSH";
    public static final Long REDIS_SSH_KEY_EXP = 60L * 60 * 24 * 30; // 30일
    private static final String DELIMITER = "-";
    private static final String EXECUTE_FAIL_BY_SESSION = "세션이 연결되지 않아 명령어 실행을 실패하였습니다.";
    private static final String BLANK_STRING = "";

    @Transactional
    public String executeCommandInShell(String command, Long sshInfoId) {
        SSHCommandExecutor executor = getSshExecutor(sshInfoId);

        if(executor == null){
            return EXECUTE_FAIL_BY_SESSION;
        }

        return executor.executeCommandInShell(command);
    }

    @Transactional
    public String executeCommandOnce(String command, Long sshInfoId) {
        SSHCommandExecutor executor = getSshExecutor(sshInfoId);

        if(executor == null){
            return BLANK_STRING;
        }

        return executor.executeCommandOnce(command);
    }

    @Scheduled(cron = "0 32 12 * * *") // 매일 12시 32분
    public void checkSshConnection(){
        List<SshInfo> sshInfos = sshInfoRepository.findAll();

        sshInfos.forEach(sshInfo -> {
            sshInfo.updateIsWorking(checkConnectionValid(sshInfo.getRemoteHost(), sshInfo.getRemoteName(), sshInfo.getRemoteKeyPath()));
        });
    }

    public boolean checkConnectionValid(String remoteHost, String remoteName, String remoteKeyPath) {
        try {
            SSHCommandExecutor executor = new SSHCommandExecutor(remoteHost, remoteName, remoteKeyPath);

            // SSH 접속 후 간단한 명령어 실행
            String response = executor.executeCommandOnce("uptime");

            // 유효성 체크가 끝나면 세션 닫기
            executor.close();
            return response.contains("load average");
        } catch (Exception e) {
            return false;
        }
    }

    // SSH 세션 종료
    public void closeSession(Long sshInfoId) {
        SSHCommandExecutor executor = executorSession.get(sshInfoId);

        if (executor != null) {
            executor.close();
        }
    }

    private synchronized SSHCommandExecutor getSshExecutor(Long sshInfoId) {
        // 세션이 열려있으면 그대로 반환
        SSHCommandExecutor executor = executorSession.get(sshInfoId);
        if(executor != null && executor.isConnected()){
            return executor;
        }

        // SSH 정보 가져오기
        SshInfoDTO sshInfoDTO = getSshInfo(sshInfoId);
        if (sshInfoDTO == null) {
            log.info("SSH 정보가 존재하지 않거나 작동하지 않습니다. SSH 정보 ID: " + sshInfoId);
            return null;
        }

        // 새로운 세션 생성 후 저장
        try {
            SSHCommandExecutor newExecutor = new SSHCommandExecutor(sshInfoDTO.remoteHost(), sshInfoDTO.remoteName(), sshInfoDTO.remoteKeyPath());
            executorSession.put(sshInfoId, newExecutor);
            return newExecutor;
        } catch (Exception e) {
            log.info("SSH 세션 연결 중 예외가 발생했습니다. SSH 정보 ID: " + sshInfoId + " 오류: " + e.getMessage());
            return null;
        }
    }

    private SshInfoDTO getSshInfo(Long sshInfoId) {
        String sshInfoStr = redisService.getDataInStr(REDIS_SSH_KEY, sshInfoId.toString());

        // 1st 레디스에 값이 있으면 파싱하여 바로 반환
        if (sshInfoStr != null) {
            return convertStrToSshInfo(sshInfoStr);
        }

        // 2nd 값이 없으면 DB에서 가져옴
        SshInfo sshInfoInDB = sshInfoRepository.findById(sshInfoId).orElseThrow(
                () -> new CustomException(ExceptionCode.SSH_NOT_FOUND)
        );

        // remoteHost, remoteName, keyPath를 하나의 문자로 합쳐서 레디스에 저장
        String combinedInfo = String.join(DELIMITER, sshInfoInDB.getRemoteHost(), sshInfoInDB.getRemoteName(), sshInfoInDB.getRemoteKeyPath());
        redisService.storeValue(REDIS_SSH_KEY, sshInfoId.toString(), combinedInfo, REDIS_SSH_KEY_EXP);

        return new SshInfoDTO(sshInfoInDB.getRemoteHost(), sshInfoInDB.getRemoteName(), sshInfoInDB.getRemoteKeyPath());
    }

    private SshInfoDTO convertStrToSshInfo(String sshInfoStr) {
        String[] parts = sshInfoStr.split(DELIMITER);

        if (parts.length != 3) {
            log.info("잘못된 SSH 정보 형식입니다.");
            return null;
        }

        return new SshInfoDTO(parts[0], parts[1], parts[2]);
    }
}