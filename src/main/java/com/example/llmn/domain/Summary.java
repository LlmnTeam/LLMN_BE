package com.example.llmn.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "summary_tb")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Summary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(columnDefinition="TEXT")
    private String content;

    @Builder
    public Summary(Project project, String content) {
        this.project = project;
        this.content = content;
    }
}
