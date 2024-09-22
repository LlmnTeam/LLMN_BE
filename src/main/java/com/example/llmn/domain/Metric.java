package com.example.llmn.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "metric_tb")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Metric extends TimeStamp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column
    private double cpuUsage;

    @Column
    private double totalMemory;

    @Column
    private double usedMemory;

    @Column
    private double totalBytesReceived;

    @Column
    private double totalBytesSent;

    @Builder
    public Metric(User user, double cpuUsage, double totalMemory, double usedMemory, double totalBytesReceived, double totalBytesSent) {
        this.user = user;
        this.cpuUsage = cpuUsage;
        this.totalMemory = totalMemory;
        this.usedMemory = usedMemory;
        this.totalBytesReceived = totalBytesReceived;
        this.totalBytesSent = totalBytesSent;
    }
}
