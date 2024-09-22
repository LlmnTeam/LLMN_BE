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
    private String remoteName;

    @Column
    private String remoteHost;

    @Column
    private String remoteKeyPath;

    @Builder
    public SshInfo(String remoteHost, String remoteName, String remoteKeyPath) {
        this.remoteName = remoteName;
        this.remoteHost = remoteHost;
        this.remoteKeyPath = remoteKeyPath;
    }
}
