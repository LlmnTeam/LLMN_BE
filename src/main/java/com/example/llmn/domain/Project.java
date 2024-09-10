package com.example.llmn.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "project_tb")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Project extends TimeStamp{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column
    private String serviceName;

    @Column
    private String containerName;

    @Column
    private String description;

    @Column
    private boolean isWorking;

    @Column
    private boolean containerWorking;

    @Builder
    public Project(User user, String serviceName, String containerName, String description) {
        this.user = user;
        this.serviceName = serviceName;
        this.containerName = containerName;
        this.description = description;
        this.isWorking = true;
        this.containerWorking = false;
    }
}
