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

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ssh_id")
    private SshInfo sshInfo;

    @Builder
    public User(Long id, String nickName, String email, String password, SshInfo sshInfo) {
        this.id = id;
        this.nickName = nickName;
        this.email = email;
        this.password = password;
        this.sshInfo = sshInfo;
    }

    public void updatePassword (String password) {
        this.password  = password;
    }
}
