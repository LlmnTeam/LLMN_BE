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

    @Column
    private double cpuUsage;

    @Column
    private long totalMemory;

    @Column
    private long usedMemory;

    @Column
    private long totalBytesReceived;

    @Column
    private long totalBytesSent;

    @Builder
    public Metric(double cpuUsage, long totalMemory, long usedMemory, long totalBytesReceived, long totalBytesSent) {
        this.cpuUsage = cpuUsage;
        this.totalMemory = totalMemory;
        this.usedMemory = usedMemory;
        this.totalBytesReceived = totalBytesReceived;
        this.totalBytesSent = totalBytesSent;
    }
}
