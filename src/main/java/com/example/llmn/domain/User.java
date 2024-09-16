package com.example.llmn.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_tb")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class User extends TimeStamp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private String nickName;

    @Column
    private String email;

    @Column
    private String password;

    @Column
    @Enumerated(EnumType.STRING)
    private UserRole role;

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
    public User(Long id, String nickName, String email, String password, UserRole role, boolean isLocal, String remoteName, String remoteHost, String remotePort, String remoteKeyPath) {
        this.id = id;
        this.nickName = nickName;
        this.email = email;
        this.password = password;
        this.role = role;
        this.isLocal = isLocal;
        this.remoteName = remoteName;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.remoteKeyPath = remoteKeyPath;
    }

    public void updatePassword (String password) {
        this.password  = password;
    }
}
