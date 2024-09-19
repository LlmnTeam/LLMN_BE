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
    private String projectName;

    @Column
    private String containerName;

    @Column
    @Enumerated(EnumType.STRING)
    private ContainerStatus containerStatus;

    @Column
    private String description;

    @Column
    private boolean isWorking;

    @Builder
    public Project(User user, String projectName, String containerName, String description, ContainerStatus containerStatus) {
        this.user = user;
        this.projectName = projectName;
        this.containerName = containerName;
        this.containerStatus = containerStatus;
        this.description = description;
        this.isWorking = true;
    }

    public void updateProject(String projectName, String containerName, String description, ContainerStatus containerStatus){
        this.projectName = projectName;
        this.containerName = containerName;
        this.description = description;
        this.containerStatus = containerStatus;
    }
}
