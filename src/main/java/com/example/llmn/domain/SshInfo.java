package com.example.llmn.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ssh_tb")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class SshInfo extends TimeStamp{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private boolean isLocal;

    @Column
    private String remoteName;

    @Column
    private String remoteHost;

    @Column
    private String remotePort;

    @Column
    private String remoteKeyPath;

    @Builder
    public SshInfo(boolean isLocal, String remoteName, String remoteHost, String remotePort, String remoteKeyPath) {
        this.isLocal = isLocal;
        this.remoteName = remoteName;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.remoteKeyPath = remoteKeyPath;
    }
}
