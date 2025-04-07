package com.example.llmn.domain.user.model.response;

import com.example.llmn.domain.remote.ServerInstance;

public record CloudInfoDTO(
        String remoteName,
        String remoteHost
) {
    public static CloudInfoDTO from(ServerInstance serverInstance) {
        return new CloudInfoDTO(
                serverInstance.getRemoteName(),
                serverInstance.getRemoteHost()
        );
    }
}
