package com.example.llmn.domain.remote;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.metric.MetricRepository;
import com.example.llmn.domain.remote.model.SshInfoDTO;
import com.example.llmn.domain.user.User;
import com.example.llmn.domain.user.UserRepository;
import com.example.llmn.domain.user.model.request.SshInfoReq;
import com.example.llmn.integration.minasshd.MinaSshdService;
import com.example.llmn.integration.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.llmn.common.constants.GlobalConstants.DELIMITER;
import static com.example.llmn.common.utils.FileUtils.*;
import static com.example.llmn.common.utils.FileUtils.writeFile;
import static com.example.llmn.integration.minasshd.MinaSshdConstants.*;
import static com.example.llmn.integration.redis.RedisConstants.REDIS_KEY_SSH;
import static com.example.llmn.integration.redis.RedisConstants.REDIS_EXP_SSH;

@Service
@RequiredArgsConstructor
@Slf4j
public class SSHService {

    private final RedisService redisService;
    private final UserRepository userRepository;
    private final SshInfoRepository sshInfoRepository;
    private final MetricRepository metricRepository;
    private final Map<Long, MinaSshdService> executorSession = new ConcurrentHashMap<>();

    @Transactional
    public void initCommend(Long userId){
        Long monitoringSshId = userRepository.findMonitoringSshId(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        MinaSshdService executor = getSshExecutor(monitoringSshId, true);
        executor.flushInitialMessage();
    }

    @Transactional
    public String executeCommandInShell(String command, boolean isFirstExecution, Long userId){
        Long monitoringSshId = userRepository.findMonitoringSshId(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

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
        Long monitoringSshId = userRepository.findMonitoringSshId(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        MinaSshdService executor = executorSession.get(monitoringSshId);
        if (executor != null) {
            executor.close();
            executorSession.remove(monitoringSshId);
        }
    }

    @Transactional
    public void executeSigInt(Long userId){
        Long monitoringSshId = userRepository.findMonitoringSshId(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));

        MinaSshdService executor = getSshExecutor(monitoringSshId, false);
        executor.sendSigint();
    }

    public boolean checkConnectionValid(String remoteHost, String remoteName, String remoteKeyPath) {
        MinaSshdService executor = new MinaSshdService(remoteHost, remoteName, remoteKeyPath);
        String response = executor.executeCommandOnce(UPTIME_COMMAND);
        executor.close();

        return response.contains(UPTIME_COMMAND_RESPONSE);
    }

    @Transactional
    public List<SshInfo> saveSshInfos(List<SshInfoReq> requestSshInfos, User user) {
        List<SshInfo> sshInfos = new ArrayList<>();

        requestSshInfos.forEach(sshInfoReq -> {
            SshInfo sshInfo = SshInfo.builder()
                    .user(user)
                    .remoteName(sshInfoReq.remoteName())
                    .remoteHost(sshInfoReq.remoteHost())
                    .remoteKeyPath(sshInfoReq.remoteKeyPath())
                    .build();

            sshInfoRepository.save(sshInfo);
            sshInfos.add(sshInfo);
        });

        return sshInfos;
    }

    @Transactional
    public List<SshInfo> addNewSshConfigurations(List<SshInfo> existingSshInfos, List<SshInfoReq> requestedSshConfigs, User user) {
        Set<String> existingHostAddresses = extractExistingHostAddresses(existingSshInfos);

        // 기존에 없는 새 호스트 주소만 필터링 후 저장
        return requestedSshConfigs.stream()
                .filter(sshInfoReq -> !existingHostAddresses.contains(sshInfoReq.remoteHost()))
                .map(sshInfoReq -> createAndSaveSshConfig(sshInfoReq, user))
                .toList();
    }

    @Transactional
    public void updateExistingSshConfigurations(List<SshInfo> existingSshConfigs, List<SshInfoReq> requestedSshConfigs) {
        // Map<String(호스트 주소), SshInfoReq(SSH 구성)> 형태로 변환
        Map<String, SshInfoReq> requestedConfigsMap = mapHostToSshConfigRequest(requestedSshConfigs);

        // 각 기존 SSH 구성을 순회하면서 업데이트 또는 삭제
        existingSshConfigs.forEach(sshInfo -> {
            if (isHostInRequestedConfigs(sshInfo, requestedConfigsMap)) {
                SshInfoReq updatedConfig = requestedConfigsMap.get(sshInfo.getRemoteHost());
                sshInfo.updateSshInfo(updatedConfig.remoteHost(), updatedConfig.remoteName(), updatedConfig.remoteKeyPath(), true);
            } else {
                metricRepository.deleteBySShInfoId(sshInfo.getId());
                sshInfoRepository.delete(sshInfo);
            }
        });
    }

    public SshInfo findMonitoringSshInfo(Long userId, String monitoringSshHost) {
        return sshInfoRepository.findByUserId(userId).stream()
                .filter(sshInfo -> sshInfo.getRemoteHost().equals(monitoringSshHost))
                .findFirst()
                .orElseThrow(() -> new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT));
    }

    @Transactional
    public void setMonitoringSshInfo(String monitoringSshHost, List<SshInfo> sshInfos, User user) {
        Optional<SshInfo> monitoringSshInfo = sshInfos.stream()
                .filter(sshInfo -> sshInfo.getRemoteHost().equals(monitoringSshHost))
                .findFirst();

        if (monitoringSshInfo.isEmpty())
            throw new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT);

        user.updateMonitoringSshInfo(monitoringSshInfo.get().getId());
    }

    public Path uploadSSHKey(MultipartFile file) {
        validateFileExist(file);
        createDirectoryIfNotExist(SSH_DIRECTORY);

        Path path = getFilePath(SSH_DIRECTORY, file);
        writeFile(file, path);

        return path;
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

    private Map<String, SshInfoReq> mapHostToSshConfigRequest(List<SshInfoReq> requestedSshConfigs) {
        return requestedSshConfigs.stream()
                .collect(Collectors.toMap(
                        SshInfoReq::remoteHost,
                        Function.identity(),
                        (existing, replacement) -> existing // 중복 키 발생 시 기존 값 유지
                ));
    }

    private boolean isHostInRequestedConfigs(SshInfo sshInfo, Map<String, SshInfoReq> newSshInfoMap) {
        return newSshInfoMap.containsKey(sshInfo.getRemoteHost());
    }

    private Set<String> extractExistingHostAddresses(List<SshInfo> existingSshInfos) {
        return existingSshInfos.stream()
                .map(SshInfo::getRemoteHost)
                .collect(Collectors.toSet());
    }

    private SshInfo createAndSaveSshConfig(SshInfoReq sshInfoReq, User user) {
        SshInfo newSshConfig = SshInfo.builder()
                .user(user)
                .remoteHost(sshInfoReq.remoteHost())
                .remoteName(sshInfoReq.remoteName())
                .remoteKeyPath(sshInfoReq.remoteKeyPath())
                .build();

        sshInfoRepository.save(newSshConfig);

        return newSshConfig;
    }
}