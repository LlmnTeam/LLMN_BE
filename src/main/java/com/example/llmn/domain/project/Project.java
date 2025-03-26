package com.example.llmn.domain.project;

import com.example.llmn.domain.docker.ContainerStatus;
import com.example.llmn.domain.remote.SshInfo;
import com.example.llmn.common.entity.BaseEntity;
import com.example.llmn.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "project_tb")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Project extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ssh_info_id")
    private SshInfo sshInfo;

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
    private boolean isUrgent;

    @Builder
    public Project(User user, SshInfo sshInfo, String projectName, String containerName, String description, ContainerStatus containerStatus, boolean isUrgent) {
        this.user = user;
        this.sshInfo = sshInfo;
        this.projectName = projectName;
        this.containerName = containerName;
        this.containerStatus = containerStatus;
        this.description = description;
        this.isUrgent = isUrgent;
    }

    public void updateProject(String projectName, String containerName, String description, ContainerStatus containerStatus){
        this.projectName = projectName;
        this.containerName = containerName;
        this.description = description;
        this.containerStatus = containerStatus;
    }

    public void updateIsUrgent(boolean isUrgent){
        this.isUrgent = isUrgent;
    }

    public boolean isConnected(){
        return containerStatus.equals(ContainerStatus.NOT_CONNECTED);
    }
    
    public boolean isNotOwnedBy(Long userId){
        return !user.getId().equals(userId);
    }

    public boolean isProjectRelatedToKeyword(String keyword) {
        String lowerCaseKeyword = keyword.toLowerCase();
        String projectName = getProjectName().toLowerCase();
        String containerName = getContainerName().toLowerCase();

        return projectName.contains(lowerCaseKeyword)
                || containerName.contains(lowerCaseKeyword)
                || lowerCaseKeyword.contains(projectName)
                || lowerCaseKeyword.contains(containerName);
    }
}
