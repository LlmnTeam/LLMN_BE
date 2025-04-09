package com.example.llmn.domain.metric;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MetricScheduler {

    private final MetricRepository metricRepository;
    private final MetricService metricService;

    @Scheduled(cron = "0 0/10 * * * ?")
    @Transactional
    public void scheduleSystemMetricsCollection() {
        List<Metric> allMetrics = metricService.gatherMetricsForUsers();
        metricRepository.saveAll(allMetrics);
    }
}
