package com.example.llmn.domain.metric;

import com.example.llmn.common.entity.BaseEntity;
import com.example.llmn.domain.remote.ServerInstance;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "metric_tb")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Metric extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ssh_info_id")
    private ServerInstance serverInstance;

    @Column
    private Double cpuUsage;

    @Column
    private Double totalMemory;

    @Column
    private Double usedMemory;

    @Column
    private Double totalBytesReceived;

    @Column
    private Double totalBytesSent;

    @Builder
    public Metric(ServerInstance serverInstance, Double cpuUsage, Double totalMemory, Double usedMemory, Double totalBytesReceived, Double totalBytesSent) {
        this.serverInstance = serverInstance;
        this.cpuUsage = cpuUsage;
        this.totalMemory = totalMemory;
        this.usedMemory = usedMemory;
        this.totalBytesReceived = totalBytesReceived;
        this.totalBytesSent = totalBytesSent;
    }
}
