package com.example.llmn.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "log_tb")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Log extends TimeStamp{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column
    private String type;

    @Column
    private String content;
}
