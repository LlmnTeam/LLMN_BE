package com.example.llmn.service;

import com.example.llmn.controller.DTO.MetricResponse;
import com.example.llmn.controller.DTO.UserResponse;
import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import com.example.llmn.domain.SummaryType;
import com.example.llmn.repository.SshInfoRepository;
import com.example.llmn.repository.SummaryRepository;
import com.example.llmn.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashBoardService {

    private final SummaryRepository summaryRepository;
    private final UserRepository userRepository;
    private final SshInfoRepository sshInfoRepository;
    private final MetricService metricService;

    private static final String NOT_AVAILABLE = "N/A";
    private static final String SORT_BY_DATE = "createdDate";
    private static final String NO_SUMMARY_DATA = "요약된 내용이 존재하지 않습니다.";

    @Transactional
    public UserResponse.FindDashboardDTO findDashboard(Long userId) {
        Long sshInfoId = findMonitoringSshId(userId);
        String remoteHost = findRemoteHost(sshInfoId);

        MetricResponse.FindCurrentMetricDTO currentMetric = metricService.findCurrentMetric(sshInfoId);
        MetricResponse.FindMetricHistoryDTO metricHistory = metricService.findMetricHistory(24, sshInfoId);
        String hourlySummary = findLatestHourlySummary();

        return new UserResponse.FindDashboardDTO(
                remoteHost,
                formatCpuUsage(currentMetric),
                formatMemoryUsage(currentMetric),
                formatNetworkReceived(currentMetric),
                formatNetworkSent(currentMetric),
                hourlySummary,
                metricHistory.cpuMetrics(),
                metricHistory.memoryMetrics(),
                metricHistory.networkInMetrics(),
                metricHistory.networkOutMetrics());
    }

    private String formatCpuUsage(MetricResponse.FindCurrentMetricDTO currentMetric) {
        return Optional.ofNullable(currentMetric)
                .map(metric -> String.format("%.2f%%", metric.cpuUsage()))
                .orElse(NOT_AVAILABLE);
    }

    private String formatMemoryUsage(MetricResponse.FindCurrentMetricDTO currentMetric) {
        return Optional.ofNullable(currentMetric)
                .filter(metric -> metric.totalMemory() > 0)
                .map(metric -> String.format("%.2f%%", (metric.usedMemory() / metric.totalMemory()) * 100))
                .orElse(NOT_AVAILABLE);
    }

    private String formatNetworkReceived(MetricResponse.FindCurrentMetricDTO currentMetric) {
        return Optional.ofNullable(currentMetric)
                .map(metric -> String.format("%.2f MB", metric.networkReceived()))
                .orElse(NOT_AVAILABLE);
    }

    private String formatNetworkSent(MetricResponse.FindCurrentMetricDTO currentMetric) {
        return Optional.ofNullable(currentMetric)
                .map(metric -> String.format("%.2f MB", metric.networkSent()))
                .orElse(NOT_AVAILABLE);
    }

    private Long findMonitoringSshId(Long userId) {
        return userRepository.findMonitoringSshId(userId)
                .orElseThrow(() -> new CustomException(ExceptionCode.USER_NOT_FOUND));
    }

    private String findRemoteHost(Long sshInfoId) {
        return sshInfoRepository.findHostById(sshInfoId)
                .orElseThrow(() -> new CustomException(ExceptionCode.SSH_NOT_FOUND));
    }

    private String findLatestHourlySummary(){
        Pageable pageable = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, SORT_BY_DATE));
        Page<String> page = summaryRepository.findContentByType(SummaryType.HOURLY, pageable);
        return page.hasContent() ? page.getContent().get(0) : NO_SUMMARY_DATA;
    }
}
