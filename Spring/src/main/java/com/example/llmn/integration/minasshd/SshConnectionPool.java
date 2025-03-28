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

            // 사용 가능한 연결 찾기
            PooledSshClient pooledClient;
            while (!pool.isEmpty()) {
                pooledClient = pool.poll();

                // 유효한 연결인지 확인
                if (pooledClient.client.isConnected()) {
                    pooledClient.markAsUsed();
                    return pooledClient.client;
                } else { // 끊어진 연결 정리
                    pooledClient.client.closeQuietly();
                }
            }

            try {
                return new SecureShellClient(
                        config.remoteHost(), config.remoteName(), config.remoteKeyPath());
            } catch (Exception e) {
                throw new CustomException(ExceptionCode.SSH_CONNECT_FAIL);
            }
        }
    }

    public void returnConnection(SshInfoDTO config, SecureShellClient client) {
        String poolKey = buildPoolKey(config.remoteHost(), config.remoteName());

        synchronized (getOrCreatePoolLock(poolKey)) {
            if (!client.isConnected()) {
                client.closeQuietly();
                return;
            }

            Queue<PooledSshClient> pool = getOrCreatePool(poolKey);
            if (pool.size() < MAX_POOL_SIZE_PER_HOST) {
                pool.offer(new PooledSshClient(client)); // 풀에 여유가 있으면 반환
            } else {
                client.closeQuietly(); // 풀이 가득 찼으면 연결 종료
            }
        }
    }

    @Scheduled(fixedRate = 60000) // 1분마다 실행
    public void cleanupIdleConnections() {
        for (Map.Entry<String, Queue<PooledSshClient>> entry : connectionPools.entrySet()) {
            String poolKey = entry.getKey();
            synchronized (getOrCreatePoolLock(poolKey)) {
                Queue<PooledSshClient> pool = entry.getValue();

                // 유효한 연결만 임시 목록에 보관
                List<PooledSshClient> activeConnections = new ArrayList<>();

                while (!pool.isEmpty()) {
                    PooledSshClient client = pool.poll();

                    if (client.client.isConnected() && !client.isIdle()) {
                        activeConnections.add(client);
                    } else { // 끊어졌거나 오래된 연결 종료
                        client.client.closeQuietly();
                    }
                }

                // 유효한 연결만 풀에 다시 추가
                pool.addAll(activeConnections);
            }
        }
    }

    private String buildPoolKey(String host, String username) {
        return username + "@" + host;
    }

    private Queue<PooledSshClient> getOrCreatePool(String poolKey) {
        return connectionPools.computeIfAbsent(poolKey, k -> new LinkedList<>());
    }

    private final Map<String, Object> poolLocks = new ConcurrentHashMap<>();

    private Object getOrCreatePoolLock(String poolKey) {
        return poolLocks.computeIfAbsent(poolKey, k -> new Object());
    }
}
