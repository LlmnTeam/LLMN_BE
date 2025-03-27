package com.example.llmn.domain.user.model.response;

import com.example.llmn.domain.remote.SshInfo;

public record SshInfoRes(
        Long id,
        String remoteName,
        String remoteHost,
        String remoteKeyPath,
        boolean isWorking
) {
    public static SshInfoRes from(SshInfo sshInfo) {
        return new SshInfoRes(
                sshInfo.getId(),
                sshInfo.getRemoteName(),
                sshInfo.getRemoteHost(),
                sshInfo.getRemoteKeyPath(),
                sshInfo.isWorking()
        );
    }
}
