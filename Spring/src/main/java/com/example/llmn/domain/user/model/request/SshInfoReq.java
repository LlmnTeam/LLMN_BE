package com.example.llmn.domain.user.model.request;

public record SshInfoReq(
        String remoteName,
        String remoteHost,
        String remoteKeyPath
) {}