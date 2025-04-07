package com.example.llmn.domain.remote.model;

import com.example.llmn.domain.remote.ServerInstance;

public record SshInfoDTO(String remoteHost,
                         String remoteName,
                         String remoteKeyPath) {

    public SshInfoDTO(ServerInstance serverInstance) {
        this(
                serverInstance.getRemoteHost(),
                serverInstance.getRemoteName(),
                serverInstance.getRemoteKeyPath()
        );
    }
}

