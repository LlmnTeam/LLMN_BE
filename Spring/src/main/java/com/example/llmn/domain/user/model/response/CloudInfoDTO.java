package com.example.llmn.domain.user.model.response;

import com.example.llmn.domain.remote.SshInfo;

public record CloudInfoDTO(
        String remoteName,
        String remoteHost
) {
    public static CloudInfoDTO from(SshInfo sshInfo) {
        return new CloudInfoDTO(
                sshInfo.getRemoteName(),
                sshInfo.getRemoteHost()
        );
    }
}
