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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column
    private String remoteName;

    @Column
    private String remoteHost;

    @Column
    private String remoteKeyPath;

    @Builder
    public SshInfo(User user, String remoteHost, String remoteName, String remoteKeyPath) {
        this.user = user;
        this.remoteName = remoteName;
        this.remoteHost = remoteHost;
        this.remoteKeyPath = remoteKeyPath;
    }

    public void updateSshInfo(String remoteHost, String remoteName, String remoteKeyPath){
        this.remoteHost = remoteHost;
        this.remoteName = remoteName;
        this.remoteKeyPath = remoteKeyPath;
    }
}
