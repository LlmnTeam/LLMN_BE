package com.example.llmn.domain.remote;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.metric.MetricRepository;
import com.example.llmn.domain.remote.model.SshInfoDTO;
import com.example.llmn.domain.user.User;
import com.example.llmn.domain.user.UserRepository;
import com.example.llmn.domain.user.model.request.SshInfoReq;
import com.example.llmn.integration.minasshd.SecureShellClient;
import com.example.llmn.integration.minasshd.SessionInfo;
import com.example.llmn.integration.minasshd.SshConnectionPool;
import com.example.llmn.integration.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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
@Transactional(readOnly = true)
public class SecureShellManager {

    private final RedisService redisService;
    private final UserRepository userRepository;
    private final SshInfoRepository sshInfoRepository;
    private final MetricRepository metricRepository;
    private final SshConnectionPool sshConnectionPool;

    private final Map<Long, SessionInfo> activeSessionsById = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> userSessionMap = new ConcurrentHashMap<>();
    private final Map<Long, Object> userLocks = new ConcurrentHashMap<>();

    public void initializeShellSession(Long userId) {
        Long monitoringSshId = getUserMonitoringSshId(userId);

        Object userLock = getUserLock(userId);
        synchronized (userLock) {
            validateUserSessionLimit(userId);

            SecureShellClient sshClient = getOrCreateSshClient(monitoringSshId, true, userId);
            sshClient.clearInitialConnectionMessages();
        }
    }

    public String executeShellCommand(String command, boolean isInitialCommand, Long userId) {
        Long monitoringSshId = getUserMonitoringSshId(userId);

        Object userLock = getUserLock(userId);
        synchronized (userLock) {
            SecureShellClient sshClient = getOrCreateSshClient(monitoringSshId, isInitialCommand, userId);

            SessionInfo sessionInfo = activeSessionsById.get(monitoringSshId);
            if (sessionInfo != null) {
                sessionInfo.updateAccessTime();
                sessionInfo.incrementCommandCount();
            }

            return sshClient.runCommandInInteractiveShell(command);
        }
    }

    public String executeOneTimeCommand(String command, Long sshInfoId) {
        SshInfoDTO sshConfig = getConnectionConfig(sshInfoId);
        if (sshConfig == null)
            throw new CustomException(ExceptionCode.SSH_NOT_FOUND);

        SecureShellClient sshClient = sshConnectionPool.borrowConnection(sshConfig);
        try {
            return sshClient.runSingleCommand(command);
        } finally {
            sshConnectionPool.returnConnection(sshConfig, sshClient);
        }
    }

    public void terminateShellSession(Long userId) {
        Long monitoringSshId = getUserMonitoringSshId(userId);

        Object userLock = getUserLock(userId);
        synchronized (userLock) {
            SessionInfo sessionInfo = activeSessionsById.get(monitoringSshId);

            if (sessionInfo != null) {
                sessionInfo.client.closeQuietly();

                activeSessionsById.remove(monitoringSshId);

                Set<Long> userSessions = userSessionMap.get(userId);
                if (userSessions != null) {
                    userSessions.remove(monitoringSshId);
                    if (userSessions.isEmpty())
                        userSessionMap.remove(userId);
                }
            }
        }
    }

    public void sendInterruptSignal(Long userId) {
        Long monitoringSshId = getUserMonitoringSshId(userId);

        Object userLock = getUserLock(userId);
        synchronized (userLock) {
            SecureShellClient sshClient = getOrCreateSshClient(monitoringSshId, false, userId);
            sshClient.sendInterruptSignal();

            SessionInfo sessionInfo = activeSessionsById.get(monitoringSshId);
            if (sessionInfo != null)
                sessionInfo.updateAccessTime();
        }
    }

    public SecureShellClient getOrCreateSshClient(Long sshInfoId, boolean forceNewConnection, Long userId) {
        SessionInfo existingSession = activeSessionsById.get(sshInfoId);

        // 1. 강제 새 연결이 아니고 기존 세션이 있는 경우
        if (!forceNewConnection && existingSession != null) {
            // 1.1 연결이 끊어진 경우 - 재연결 시도
            if (!existingSession.client.isConnected()) {
                log.info("<SSHD> 세션 {}의 연결이 끊김, 재연결 시도", sshInfoId);

                boolean reconnected = existingSession.client.attemptReconnect();
                if (reconnected) {
                    existingSession.updateAccessTime();
                    return existingSession.client;
                } else {
                    log.warn("<SSHD> 세션 {} 재연결 실패, 새 연결 생성", sshInfoId);

                    // 기존 연결 정리
                    existingSession.client.closeQuietly();
                    activeSessionsById.remove(sshInfoId);

                    Set<Long> userSessions = userSessionMap.get(userId);
                    if (userSessions != null) {
                        userSessions.remove(sshInfoId);
                    }
                }
            } else {
                // 1.2 정상 연결된 세션이면 그대로 사용
                existingSession.updateAccessTime();
                return existingSession.client;
            }
        }

        // 2. 세션 수 제한 확인
        if (activeSessionsById.size() >= GLOBAL_MAX_SESSIONS) {
            cleanupIdleSessions();

            if (activeSessionsById.size() >= GLOBAL_MAX_SESSIONS) // 여전히 제한 초과 시
                throw new CustomException(ExceptionCode.SSH_CONNECTION_LIMIT_EXCEEDED);
        }

        // 3. 기존 연결이 있지만 새로운 필요한 경우 기존 연결 정리
        if (existingSession != null) {
            existingSession.client.closeQuietly();
            activeSessionsById.remove(sshInfoId);

            Set<Long> userSessions = userSessionMap.get(userId);
            if (userSessions != null)
                userSessions.remove(sshInfoId);
        }

        // 4. SSH 정보 가져오고 새 클라이언트 생성과 세션 정보 업데이트
        SshInfoDTO sshConfig = getConnectionConfig(sshInfoId);
        if (sshConfig == null)
            throw new CustomException(ExceptionCode.SSH_NOT_FOUND);

        try {
            SecureShellClient newClient = new SecureShellClient(
                    sshConfig.remoteHost(),
                    sshConfig.remoteName(),
                    sshConfig.remoteKeyPath()
            );

            SessionInfo newSession = new SessionInfo(userId, newClient);
            activeSessionsById.put(sshInfoId, newSession);

            userSessionMap.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                    .add(sshInfoId);

            return newClient;
        } catch (Exception e) {
            throw new CustomException(ExceptionCode.SSH_CONNECT_FAIL);
        }
    }

    @Scheduled(fixedRate = 60 * 60 * 1000) // 1시간마다 실행
    public void cleanupUnusedResources() {
        // 활성 세션이 없는 사용자의 락 객체 제거
        Set<Long> usersWithoutSessions = new HashSet<>(userLocks.keySet());
        usersWithoutSessions.removeAll(userSessionMap.keySet());
        for (Long userId : usersWithoutSessions) {
            userLocks.remove(userId);
        }
    }

    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void cleanupIdleSessions() {
        List<Long> sessionsToRemove = new ArrayList<>();

        // 세션 순회하며 유휴 시간 확인
        for (Map.Entry<Long, SessionInfo> entry : activeSessionsById.entrySet()) {
            try {
                Long sessionId = entry.getKey();
                SessionInfo sessionInfo = entry.getValue();

                // 세션이 타임아웃 되었는지 확인
                if (sessionInfo.getIdleTimeMillis() > SESSION_IDLE_TIMEOUT) {
                    sessionsToRemove.add(sessionId);

                    sessionInfo.client.closeQuietly();

                    Set<Long> userSessions = userSessionMap.get(sessionInfo.userId);
                    if (userSessions != null) {
                        userSessions.remove(sessionId);
                        if (userSessions.isEmpty()) {
                            userSessionMap.remove(sessionInfo.userId);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("세션 정리 중 오류: {}", e.getMessage(), e);
                // 다음 세션으로 계속 진행
            }
        }

        for (Long sessionId : sessionsToRemove) {
            try {
                activeSessionsById.remove(sessionId);
            } catch (Exception e) {
                log.error("세션 {} 제거 중 오류: {}", sessionId, e.getMessage());
            }
        }
    }

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

    public Path uploadSSHKey(MultipartFile keyFile) {
        validateFileExist(keyFile);
        createDirectoryIfNotExist(SSH_KEYS_DIRECTORY);

        Path keyPath = getFilePath(SSH_KEYS_DIRECTORY, keyFile);
        writeFile(keyFile, keyPath);

        return keyPath;
    }

    public boolean testSshConnection(String remoteHost, String remoteName, String remoteKeyPath) {
        SecureShellClient sshClient = new SecureShellClient(remoteHost, remoteName, remoteKeyPath);
        String response = sshClient.runSingleCommand(CONNECTION_TEST_COMMAND);
        sshClient.closeQuietly();

        return response.contains(CONNECTION_TEST_SUCCESS_MARKER);
    }

    private Object getUserLock(Long userId) {
        return userLocks.computeIfAbsent(userId, k -> new Object());
    }

    private Long getUserMonitoringSshId(Long userId) {
        return userRepository.findMonitoringSshId(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));
    }

    private void validateUserSessionLimit(Long userId) {
        Set<Long> userSessions = userSessionMap.get(userId);
        if (userSessions != null && userSessions.size() >= MAX_SESSIONS_PER_USER) {
            // 1. 유효하지 않은 세션 참조 정리 (맵 불일치 방지)
            userSessions.removeIf(sessionId -> !activeSessionsById.containsKey(sessionId));

            // 2. 정리 후에도 여전히 제한을 초과하는지 확인
            if (userSessions.size() >= MAX_SESSIONS_PER_USER) {
                Optional<Long> oldestSessionId = findOldestSessionForUser(userId);

                // 3. 가장 오래된 세션 종료
                if (oldestSessionId.isPresent()) {
                    Long sessionId = oldestSessionId.get();
                    SessionInfo sessionInfo = activeSessionsById.get(sessionId);

                    if (sessionInfo != null) {
                        sessionInfo.client.closeQuietly();
                        activeSessionsById.remove(sessionId);
                        userSessions.remove(sessionId);
                    }
                } else {
                    // 4. findOldestSessionForUser가 빈 결과를 반환한 경우 (이론상 발생하지 않아야 함) => 초기화
                    log.warn("<SSHD> 사용자 {} 세션 맵 불일치 감지, 세션 맵 초기화", userId);
                    userSessions.clear();
                }
            }
        }
    }

    private Optional<Long> findOldestSessionForUser(Long userId) {
        Set<Long> userSessions = userSessionMap.get(userId);
        if (userSessions == null || userSessions.isEmpty())
            return Optional.empty();

        return userSessions.stream()
                .filter(activeSessionsById::containsKey)
                .min(Comparator.comparing(sessionId ->
                        activeSessionsById.get(sessionId).lastAccessTime));
    }

    private SshInfoDTO getConnectionConfig(Long sshInfoId) {
        return retrieveCachedSshInfo(sshInfoId)
                .orElseGet(() -> fetchConfigFromDatabase(sshInfoId)
                        .map(sshInfo -> {
                            cacheConnectionConfig(sshInfoId, sshInfo);
                            return new SshInfoDTO(
                                    sshInfo.getRemoteHost(),
                                    sshInfo.getRemoteName(),
                                    sshInfo.getRemoteKeyPath()
                            );
                        })
                        .orElseThrow(() -> new CustomException(ExceptionCode.SSH_NOT_FOUND)));
    }

    private Optional<SshInfoDTO> retrieveCachedSshInfo(Long sshInfoId) {
        String cachedConfig  = redisService.getValueInString(REDIS_KEY_SSH, sshInfoId.toString());
        if (cachedConfig  == null)
            return Optional.empty();

        return parseConfigString(cachedConfig );
    }

    private Optional<SshInfoDTO> parseConfigString(String sshInfoStr) {
        String[] parts = sshInfoStr.split(DELIMITER);
        if (parts.length != 3)
            return Optional.empty();

        return Optional.of(new SshInfoDTO(parts[0], parts[1], parts[2]));
    }

    private Optional<SshInfo> fetchConfigFromDatabase(Long sshInfoId) {
        return sshInfoRepository.findById(sshInfoId);
    }

    private void cacheConnectionConfig(Long sshInfoId, SshInfo sshInfo) {
        String configString = String.join(
                DELIMITER,
                sshInfo.getRemoteHost(),
                sshInfo.getRemoteName(),
                sshInfo.getRemoteKeyPath()
        );

        redisService.storeValue(REDIS_KEY_SSH, sshInfoId.toString(), configString, REDIS_EXP_SSH);
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