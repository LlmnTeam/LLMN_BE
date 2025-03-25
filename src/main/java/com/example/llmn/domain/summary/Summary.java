package com.example.llmn.domain.summary;

import com.example.llmn.domain.project.Project;
import com.example.llmn.common.entity.BaseEntity;
import com.example.llmn.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "summary_tb")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Summary extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(length = 50)
    @Enumerated(EnumType.STRING)
    private SummaryType summaryType;

    @Column(columnDefinition="TEXT")
    private String content;

    @Column
    private boolean isChecked;

    @Builder
    public Summary(User user, Project project, SummaryType summaryType, String content) {
        this.user = user;
        this.project = project;
        this.summaryType = summaryType;
        this.content = content;
        this.isChecked = false;
    }

    public void check(){
        this.isChecked = !isChecked;
    }
}
