package com.example.llmn.domain.remote;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.metric.MetricRepository;
import com.example.llmn.domain.user.User;
import com.example.llmn.domain.user.UserRepository;
import com.example.llmn.domain.user.model.request.SshInfoReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.llmn.common.utils.FileUtils.*;
import static com.example.llmn.common.utils.FileUtils.writeFile;
import static com.example.llmn.integration.minasshd.MinaSshdConstants.SSH_KEYS_DIRECTORY;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SshConfigService {

    private final SshInfoRepository sshInfoRepository;
    private final MetricRepository metricRepository;
    private final UserRepository userRepository;

    @Transactional
    public List<SshInfo> createSshConfigurations(List<SshInfoReq> sshConfigRequests, User user) {
        List<SshInfo> createdConfigs = new ArrayList<>();

        sshConfigRequests.forEach(sshInfoReq -> {
            SshInfo sshInfo = SshInfo.builder()
                    .user(user)
                    .remoteName(sshInfoReq.remoteName())
                    .remoteHost(sshInfoReq.remoteHost())
                    .remoteKeyPath(sshInfoReq.remoteKeyPath())
                    .build();

            sshInfoRepository.save(sshInfo);
            createdConfigs.add(sshInfo);
        });

        return createdConfigs;
    }

    public SshInfo findMonitoringSshConfig(Long userId, String monitoringSshHost) {
        return sshInfoRepository.findByUserId(userId).stream()
                .filter(config -> config.getRemoteHost().equals(monitoringSshHost))
                .findFirst()
                .orElseThrow(() -> new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT));
    }

    @Transactional
    public List<SshInfo> addNewSshConfigurations(List<SshInfo> existingConfigs, List<SshInfoReq> requestedConfigs, User user) {
        Set<String> existingHostAddresses = extractHostAddresses(existingConfigs);

        // 기존에 없는 새 호스트 주소만 필터링 후 저장
        return requestedConfigs.stream()
                .filter(sshInfoReq -> !existingHostAddresses.contains(sshInfoReq.remoteHost()))
                .map(sshInfoReq -> createAndSaveSshConfig(sshInfoReq, user))
                .toList();
    }

    @Transactional
    public void updateExistingSshConfigurations(List<SshInfo> existingSshConfigs, List<SshInfoReq> requestedSshConfigs) {
        // Map<String(호스트 주소), SshInfoReq(SSH 구성)> 형태로 변환
        Map<String, SshInfoReq> configRequestsByHost = mapHostToSshConfigRequest(requestedSshConfigs);

        // 각 기존 SSH 구성을 순회하면서 업데이트 또는 삭제
        existingSshConfigs.forEach(existingConfig -> {
            if (configRequestsByHost.containsKey(existingConfig.getRemoteHost())) {
                SshInfoReq updatedConfig = configRequestsByHost.get(existingConfig.getRemoteHost());
                existingConfig.updateSshInfo(updatedConfig.remoteHost(), updatedConfig.remoteName(), updatedConfig.remoteKeyPath(), true);
            } else {
                metricRepository.deleteBySShInfoId(existingConfig.getId());
                sshInfoRepository.delete(existingConfig);
            }
        });
    }

    @Transactional
    public void setMonitoringTarget(String monitoringSshHost, List<SshInfo> availableConfigs, User user) {
        Optional<SshInfo> matchingConfig = availableConfigs.stream()
                .filter(config -> config.getRemoteHost().equals(monitoringSshHost))
                .findFirst();

        if (matchingConfig.isEmpty())
            throw new CustomException(ExceptionCode.MONITORING_SSH_NOT_SELECT);

        user.updateMonitoringSshInfo(matchingConfig.get().getId());
    }

    public Long getUserMonitoringSshId(Long userId) {
        return userRepository.findMonitoringSshId(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));
    }

    public Path uploadSSHKey(MultipartFile keyFile) {
        validateFileExist(keyFile);
        createDirectoryIfNotExist(SSH_KEYS_DIRECTORY);

        Path keyPath = getFilePath(SSH_KEYS_DIRECTORY, keyFile);
        writeFile(keyFile, keyPath);

        return keyPath;
    }

    private Map<String, SshInfoReq> mapHostToSshConfigRequest(List<SshInfoReq> configRequests) {
        return configRequests.stream()
                .collect(Collectors.toMap(
                        SshInfoReq::remoteHost,
                        Function.identity(),
                        (existing, replacement) -> existing // 중복 키 발생 시 기존 값 유지
                ));
    }

    private Set<String> extractHostAddresses(List<SshInfo> configs) {
        return configs.stream()
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

        return sshInfoRepository.save(newSshConfig);
    }
}
