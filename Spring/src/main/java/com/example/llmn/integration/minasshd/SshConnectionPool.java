package com.example.llmn.integration.minasshd;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import com.example.llmn.domain.remote.model.SshInfoDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class SshConnectionPool {

    private final Map<String, Queue<PooledSshClient>> connectionPools = new ConcurrentHashMap<>();
    private final Map<String, Object> poolLocks = new ConcurrentHashMap<>();

    private static final int MAX_POOL_SIZE_PER_HOST = 5;
    private static final long CONNECTION_IDLE_TIMEOUT = 5 * 60 * 1000; // 5분

    private static class PooledSshClient {
        final SecureShellClient client;
        LocalDateTime lastUsed;

        PooledSshClient(SecureShellClient client) {
            this.client = client;
            this.lastUsed = LocalDateTime.now();
        }

        void markAsUsed() {
            this.lastUsed = LocalDateTime.now();
        }

        boolean isIdle() {
            return java.time.Duration.between(lastUsed, LocalDateTime.now()).toMillis() > SshConnectionPool.CONNECTION_IDLE_TIMEOUT;
        }
    }

    public SecureShellClient borrowConnection(SshInfoDTO config) {
        String poolKey = buildPoolKey(config.remoteHost(), config.remoteName());

        synchronized (getOrCreatePoolLock(poolKey)) {
            Queue<PooledSshClient> pool = getOrCreatePool(poolKey);

            SecureShellClient validClient = findValidConnectionInPool(pool);
            if (validClient != null)
                return validClient;

            return createNewConnection(config);
        }
    }

    public void returnConnection(SshInfoDTO config, SecureShellClient client) {
        String poolKey = buildPoolKey(config.remoteHost(), config.remoteName());

        synchronized (getOrCreatePoolLock(poolKey)) {
            if (!client.isConnected()) {
                closeClientQuietly(client);
                return;
            }

            Queue<PooledSshClient> pool = getOrCreatePool(poolKey);
            if (pool.size() < MAX_POOL_SIZE_PER_HOST) {
                pool.offer(new PooledSshClient(client)); // 풀에 여유가 있으면 반환
            } else {
                closeClientQuietly(client); // 풀이 가득 찼으면 연결 종료
            }
        }
    }

    @Scheduled(fixedRate = 6000000) // 100분마다 실행
    public void cleanupIdleConnections() {
        connectionPools.forEach(this::cleanupPoolConnections);
    }

    private String buildPoolKey(String host, String username) {
        return username + "@" + host;
    }

    private Queue<PooledSshClient> getOrCreatePool(String poolKey) {
        return connectionPools.computeIfAbsent(poolKey, k -> new LinkedList<>());
    }

    private Object getOrCreatePoolLock(String poolKey) {
        return poolLocks.computeIfAbsent(poolKey, k -> new Object());
    }

    // 사용 가능한 연결 찾기
    private SecureShellClient findValidConnectionInPool(Queue<PooledSshClient> pool) {
        PooledSshClient pooledClient;
        while (!pool.isEmpty()) {
            pooledClient = pool.poll();

            if (isConnectionValid(pooledClient)) {
                pooledClient.markAsUsed();
                return pooledClient.client;
            } else {
                closeClientQuietly(pooledClient.client);
            }
        }
        return null;
    }

    private boolean isConnectionValid(PooledSshClient pooledClient) {
        return pooledClient.client.isConnected();
    }

    private void closeClientQuietly(SecureShellClient client) {
        client.closeQuietly();
    }

    private SecureShellClient createNewConnection(SshInfoDTO config) {
        try {
            return new SecureShellClient(config.remoteHost(), config.remoteName(), config.remoteKeyPath());
        } catch (Exception e) {
            throw new CustomException(ExceptionCode.SSH_CONNECT_FAIL);
        }
    }

    private void cleanupPoolConnections(String poolKey, Queue<PooledSshClient> pool) {
        synchronized (getOrCreatePoolLock(poolKey)) {
            List<PooledSshClient> activeConnections = filterActiveConnections(pool);

            // 풀 비우고 활성 연결만 다시 추가
            pool.clear();
            pool.addAll(activeConnections);
        }
    }

    private List<PooledSshClient> filterActiveConnections(Queue<PooledSshClient> pool) {
        List<PooledSshClient> activeConnections = new ArrayList<>();

        for (PooledSshClient client : new ArrayList<>(pool)) {
            if (isActiveConnection(client)) {
                activeConnections.add(client);
            } else {
                closeClientQuietly(client.client);
            }
        }

        return activeConnections;
    }

    private boolean isActiveConnection(PooledSshClient client) {
        return client.client.isConnected() && !client.isIdle();
    }
}
