package com.example.llmn.domain.user.model.response;

import com.example.llmn.domain.metric.model.response.CpuMetricRes;
import com.example.llmn.domain.metric.model.response.MemoryMetricRes;
import com.example.llmn.domain.metric.model.response.NetworkInMetricRes;
import com.example.llmn.domain.metric.model.response.NetworkOutMetricRes;

import java.util.List;

public record FindDashboardRes(
        String ip,
        String cpuUsage,
        String memoryUsage,
        String networkReceived,
        String networkSent,
        String summary,
        List<CpuMetricRes> cpuHistory,
        List<MemoryMetricRes> memoryHistory,
        List<NetworkInMetricRes> networkInHistory,
        List<NetworkOutMetricRes> networkOutHistory
) {}
