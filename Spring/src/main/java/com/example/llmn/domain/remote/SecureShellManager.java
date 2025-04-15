package com.example.llmn.domain.remote;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.remote.model.SshInfoDTO;
import com.example.llmn.integration.minasshd.SecureShellClient;
import com.example.llmn.integration.minasshd.SessionInfo;
import com.example.llmn.integration.minasshd.SshConnectionPool;
import com.example.llmn.integration.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.llmn.common.constants.GlobalConstants.DELIMITER;
import static com.example.llmn.integration.minasshd.MinaSshdConstants.*;
import static com.example.llmn.integration.redis.RedisConstants.REDIS_KEY_SSH;
import static com.example.llmn.integration.redis.RedisConstants.REDIS_EXPIRY_SSH_MS;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SecureShellManager {

    private final RedisService redisService;
    private final SshConfigService sshConfigService;
    private final ServerInstanceRepository serverInstanceRepository;
    private final SshConnectionPool sshConnectionPool;

    private final Map<Long, SessionInfo> activeSessionsById = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> userSessionMap = new ConcurrentHashMap<>();
    private final Map<Long, Object> userLocks = new ConcurrentHashMap<>();

    public void initializeShellSession(Long userId) {
        Long monitoringSshId = sshConfigService.getUserMonitoringSshId(userId);
        Object userLock = getUserLock(userId);

        synchronized (userLock) {
            validateUserSessionLimit(userId);
            SecureShellClient sshClient = getOrCreateSshClient(monitoringSshId, true, userId);
            sshClient.clearInitialConnectionMessages();
        }
    }

    public String executeShellCommand(String command, boolean isInitialCommand, Long userId) {
        Long monitoringSshId = sshConfigService.getUserMonitoringSshId(userId);
        Object userLock = getUserLock(userId);

        synchronized (userLock) {
            SecureShellClient sshClient = getOrCreateSshClient(monitoringSshId, isInitialCommand, userId);
            updateSessionInfo(monitoringSshId);
            return sshClient.runCommandInInteractiveShell(command);
        }
    }

    public String executeOneTimeCommand(String command, Long serverInstanceId) {
        SshInfoDTO sshConfig = getConnectionConfig(serverInstanceId);
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
        Long monitoringSshId = sshConfigService.getUserMonitoringSshId(userId);
        Object userLock = getUserLock(userId);

        synchronized (userLock) {
            SessionInfo sessionInfo = activeSessionsById.get(monitoringSshId);
            if (sessionInfo != null) {
                closeAndRemoveSession(monitoringSshId, userId);
            }
        }
    }

    public void sendInterruptSignal(Long userId) {
        Long monitoringSshId = sshConfigService.getUserMonitoringSshId(userId);
        Object userLock = getUserLock(userId);

        synchronized (userLock) {
            SecureShellClient sshClient = getOrCreateSshClient(monitoringSshId, false, userId);
            sshClient.sendInterruptSignal();

            updateSessionAccessTime(monitoringSshId);
        }
    }

    public SecureShellClient getOrCreateSshClient(Long serverInstanceId, boolean isNewConnection, Long userId) {
        if (!isNewConnection) {
            SecureShellClient existingClient = tryUseExistingSession(serverInstanceId, userId);
            if (existingClient != null) return existingClient;
        }

        ensureSessionLimitNotExceeded();

        cleanupExistingSession(serverInstanceId, userId);

        return createNewSshClient(serverInstanceId, userId);
    }

    @Scheduled(fixedRate = 60 * 60 * 1000) // 1시간마다 실행
    public void cleanupResources() {
        // 1. 유휴 세션 정리
        List<Long> idleSessions = findIdleSessions();
        removeIdleSessions(idleSessions);
        log.info("<SSHD> 유휴 세션 정리 완료: {} 개 세션 제거됨", idleSessions.size());

        // 2. 사용자 락 정리
        Set<Long> usersWithoutSessions = findUsersWithoutActiveSessions();
        removeLocksForUsers(usersWithoutSessions);
        log.info("<SSHD> 미사용 사용자 락 정리 완료: {} 개 락 제거됨", usersWithoutSessions.size());
    }

    public boolean testSshConnection(String remoteHost, String remoteName, String remoteKeyPath) {
        SecureShellClient sshClient = new SecureShellClient(remoteHost, remoteName, remoteKeyPath);
        try {
            String response = sshClient.runSingleCommand(CONNECTION_TEST_COMMAND);
            return response.contains(CONNECTION_TEST_SUCCESS_MARKER);
        } finally {
            sshClient.closeQuietly();
        }
    }

    private Object getUserLock(Long userId) {
        return userLocks.computeIfAbsent(userId, k -> new Object());
    }

    private void updateSessionInfo(Long sessionId) {
        SessionInfo sessionInfo = activeSessionsById.get(sessionId);
        if (sessionInfo != null) {
            sessionInfo.updateAccessTime();
            sessionInfo.incrementCommandCount();
        }
    }

    private void closeAndRemoveSession(Long sessionId, Long userId) {
        SessionInfo sessionInfo = activeSessionsById.get(sessionId);
        if (sessionInfo == null) return;

        sessionInfo.client.closeQuietly();
        activeSessionsById.remove(sessionId);

        removeSessionFromUserMap(sessionId, userId);
    }

    private void removeSessionFromUserMap(Long sessionId, Long userId) {
        Set<Long> userSessions = userSessionMap.get(userId);
        if (userSessions != null) {
            userSessions.remove(sessionId);
            if (userSessions.isEmpty()) {
                userSessionMap.remove(userId);
            }
        }
    }

    private void updateSessionAccessTime(Long sessionId) {
        SessionInfo sessionInfo = activeSessionsById.get(sessionId);
        if (sessionInfo != null)
            sessionInfo.updateAccessTime();
    }

    private SecureShellClient tryUseExistingSession(Long serverInstanceId, Long userId) {
        SessionInfo existingSession = activeSessionsById.get(serverInstanceId);
        if (existingSession == null) {
            return null;
        }

        // 연결 끊김 확인 및 재연결 시도
        if (!existingSession.client.isConnected()) {
            return handleDisconnectedSession(existingSession, serverInstanceId, userId);
        }

        // 정상 연결된 세션 사용
        existingSession.updateAccessTime();
        return existingSession.client;
    }

    private SecureShellClient handleDisconnectedSession(SessionInfo session, Long serverInstanceId, Long userId) {
        log.info("<SSHD> 세션 {}의 연결이 끊김, 재연결 시도", serverInstanceId);

        if (session.client.attemptReconnect()) {
            session.updateAccessTime();
            return session.client;
        }

        log.warn("<SSHD> 세션 {} 재연결 실패, 새 연결 생성", serverInstanceId);
        removeSessionFromMaps(session, serverInstanceId, userId);
        return null;
    }

    private void removeSessionFromMaps(SessionInfo session, Long serverInstanceId, Long userId) {
        session.client.closeQuietly();
        activeSessionsById.remove(serverInstanceId);

        removeSessionFromUserMap(serverInstanceId, userId);
    }

    private void ensureSessionLimitNotExceeded() {
        if (activeSessionsById.size() >= GLOBAL_MAX_SESSIONS) {
            List<Long> idleSessions = findIdleSessions();
            removeIdleSessions(idleSessions);

            if (activeSessionsById.size() >= GLOBAL_MAX_SESSIONS) { // 여전히 제한 초과 시
                throw new CustomException(ExceptionCode.SSH_CONNECTION_LIMIT_EXCEEDED);
            }
        }
    }

    private void cleanupExistingSession(Long serverInstanceId, Long userId) {
        SessionInfo existingSession = activeSessionsById.get(serverInstanceId);
        if (existingSession != null) {
            removeSessionFromMaps(existingSession, serverInstanceId, userId);
        }
    }

    private SecureShellClient createNewSshClient(Long serverInstanceId, Long userId) {
        SshInfoDTO sshConfig = getConnectionConfig(serverInstanceId);
        if (sshConfig == null)
            throw new CustomException(ExceptionCode.SSH_NOT_FOUND);

        try {
            SecureShellClient newClient = new SecureShellClient(
                    sshConfig.remoteHost(),
                    sshConfig.remoteName(),
                    sshConfig.remoteKeyPath()
            );

            registerNewSession(newClient, serverInstanceId, userId);
            return newClient;
        } catch (Exception e) {
            throw new CustomException(ExceptionCode.SSH_CONNECT_FAIL);
        }
    }

    private void registerNewSession(SecureShellClient client, Long serverInstanceId, Long userId) {
        SessionInfo newSession = new SessionInfo(userId, client);
        activeSessionsById.put(serverInstanceId, newSession);

        userSessionMap.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(serverInstanceId);
    }

    private Set<Long> findUsersWithoutActiveSessions() {
        Set<Long> usersWithoutSessions = new HashSet<>(userLocks.keySet());
        usersWithoutSessions.removeAll(userSessionMap.keySet());
        return usersWithoutSessions;
    }

    private void removeLocksForUsers(Set<Long> userIds) {
        for (Long userId : userIds) {
            userLocks.remove(userId);
        }
    }

    private List<Long> findIdleSessions() {
        List<Long> sessionsToRemove = new ArrayList<>();

        for (Map.Entry<Long, SessionInfo> entry : activeSessionsById.entrySet()) {
            try {
                Long sessionId = entry.getKey();
                SessionInfo sessionInfo = entry.getValue();

                if (isSessionIdle(sessionInfo)) {
                    sessionsToRemove.add(sessionId);
                    closeSessionAndUpdateMaps(sessionInfo, sessionId);
                }
            } catch (Exception e) {
                log.error("세션 정리 중 오류: {}", e.getMessage(), e);
                // 다음 세션으로 계속 진행
            }
        }

        return sessionsToRemove;
    }

    private boolean isSessionIdle(SessionInfo sessionInfo) {
        return sessionInfo.getIdleTimeMillis() > SESSION_IDLE_TIMEOUT;
    }

    private void closeSessionAndUpdateMaps(SessionInfo sessionInfo, Long sessionId) {
        sessionInfo.client.closeQuietly();

        Set<Long> userSessions = userSessionMap.get(sessionInfo.userId);
        if (userSessions != null) {
            userSessions.remove(sessionId);
            if (userSessions.isEmpty()) {
                userSessionMap.remove(sessionInfo.userId);
            }
        }
    }

    private void removeIdleSessions(List<Long> sessionsToRemove) {
        for (Long sessionId : sessionsToRemove) {
            try {
                activeSessionsById.remove(sessionId);
            } catch (Exception e) {
                log.error("세션 {} 제거 중 오류: {}", sessionId, e.getMessage());
            }
        }
    }

    private void validateUserSessionLimit(Long userId) {
        Set<Long> userSessions = userSessionMap.get(userId);
        if (userSessions == null || userSessions.size() < MAX_SESSIONS_PER_USER) {
            return;
        }

        cleanInvalidUserSessions(userSessions);

        // 정리 후에도 여전히 제한을 초과하는지 확인
        if (userSessions.size() >= MAX_SESSIONS_PER_USER)
            handleSessionLimitExceeded(userSessions, userId);
    }

    private void cleanInvalidUserSessions(Set<Long> userSessions) {
        userSessions.removeIf(sessionId -> !activeSessionsById.containsKey(sessionId));
    }

    private void handleSessionLimitExceeded(Set<Long> userSessions, Long userId) {
        Optional<Long> oldestSessionId = findOldestSessionForUser(userId);

        if (oldestSessionId.isPresent()) {
            terminateOldestSession(oldestSessionId.get(), userSessions);
        } else { // 맵 불일치가 감지된 경우 초기화
            userSessions.clear();
        }
    }

    private void terminateOldestSession(Long sessionId, Set<Long> userSessions) {
        SessionInfo sessionInfo = activeSessionsById.get(sessionId);

        if (sessionInfo != null) {
            sessionInfo.client.closeQuietly();
            activeSessionsById.remove(sessionId);
            userSessions.remove(sessionId);
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

    private SshInfoDTO getConnectionConfig(Long serverInstanceId) {
        return retrieveCachedSshInfo(serverInstanceId)
                .orElseGet(() -> fetchConfigFromDatabase(serverInstanceId)
                        .map(serverInstance -> {
                            cacheConnectionConfig(serverInstanceId, serverInstance);
                            return new SshInfoDTO(serverInstance);
                        })
                        .orElseThrow(() -> new CustomException(ExceptionCode.SSH_NOT_FOUND))
                );
    }

    private Optional<SshInfoDTO> retrieveCachedSshInfo(Long serverInstanceId) {
        String cachedConfig = redisService.getValueInString(REDIS_KEY_SSH, serverInstanceId.toString());
        if (cachedConfig == null)
            return Optional.empty();

        return parseConfigString(cachedConfig);
    }

    private Optional<SshInfoDTO> parseConfigString(String sshInfoStr) {
        String[] parts = sshInfoStr.split(DELIMITER);
        if (parts.length != 3)
            return Optional.empty();

        return Optional.of(new SshInfoDTO(parts[0], parts[1], parts[2]));
    }

    private Optional<ServerInstance> fetchConfigFromDatabase(Long serverInstanceId) {
        return serverInstanceRepository.findById(serverInstanceId);
    }

    private void cacheConnectionConfig(Long serverInstanceId, ServerInstance serverInstance) {
        String configString = String.join(
                DELIMITER,
                serverInstance.getRemoteHost(),
                serverInstance.getRemoteName(),
                serverInstance.getRemoteKeyPath()
        );

        redisService.storeValue(REDIS_KEY_SSH, serverInstanceId.toString(), configString, REDIS_EXPIRY_SSH_MS);
    }
}