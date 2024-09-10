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
    private String description;

    @Builder
    public Project(User user, String serviceName, String description) {
        this.user = user;
        this.serviceName = serviceName;
        this.description = description;
    }
}
