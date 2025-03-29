package com.example.llmn.domain.remote.model;

import com.example.llmn.domain.remote.SshInfo;

public record SshInfoDTO(String remoteHost,
                         String remoteName,
                         String remoteKeyPath) {

    public SshInfoDTO(SshInfo sshInfo) {
        this(
                sshInfo.getRemoteHost(),
                sshInfo.getRemoteName(),
                sshInfo.getRemoteKeyPath()
        );
    }
}

