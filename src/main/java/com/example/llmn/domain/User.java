package com.example.llmn.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

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

    @Column
    private boolean receivingAlarm;

    @Builder
    public User(Long id, String nickName, String email, String password, SshInfo sshInfo, boolean receivingAlarm) {
        this.id = id;
        this.nickName = nickName;
        this.email = email;
        this.password = password;
        this.sshInfo = sshInfo;
        this.receivingAlarm = receivingAlarm;
    }

    public void updatePassword (String password) {
        this.password  = password;
    }

    public void updateConfiguration(String nickName, boolean receivingAlarm){
        this.nickName = nickName;
        this.receivingAlarm = receivingAlarm;
    }
}
