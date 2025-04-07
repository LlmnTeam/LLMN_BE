package com.example.llmn.domain.user.model.response;

import com.example.llmn.domain.remote.ServerInstance;

public record SshInfoRes(
        Long id,
        String remoteName,
        String remoteHost,
        String remoteKeyPath,
        boolean isWorking
) {
    public static SshInfoRes from(ServerInstance serverInstance) {
        return new SshInfoRes(
                serverInstance.getId(),
                serverInstance.getRemoteName(),
                serverInstance.getRemoteHost(),
                serverInstance.getRemoteKeyPath(),
                serverInstance.isWorking()
        );
    }
}
