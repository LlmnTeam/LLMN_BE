package com.example.llmn.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "log_tb")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class LogEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private String containerName;

    // LOW, MEDIUM, HIGH
    @Column
    private String severity;

    @Column
    private String content;

    @Column
    private LocalDateTime timestamp;

    public LogEvent(String containerName, String severity, String content, LocalDateTime timestamp) {
        this.containerName = containerName;
        this.severity = severity;
        this.content = content;
        this.timestamp = timestamp;
    }
}
