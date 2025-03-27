package com.example.llmn.domain.metric;

import java.util.regex.Pattern;

public class MetricConstants {

    private MetricConstants() {}

    public static final String METRIC_MAP_CPU_USAGE = "cpuUsage";
    public static final String METRIC_MAP_TOTAL_MEMORY = "totalMemory";
    public static final String METRIC_MAP_USED_MEMORY = "usedMemory";
    public static final String METRIC_MAP_NETWORK_RECEIVED = "networkReceived";
    public static final String METRIC_MAP_NETWORK_SENT = "networkSent";
    public static final String METRIC_MAP_DAILY_NET_RECEIVED = "dailyReceived";
    public static final String METRIC_MAP_DAILY_NET_SENT = "dailySent";

    public static final String COMMAND_TOP = "top -b -n1 | grep \"Cpu(s)\\|Mem\"";
    public static final String COMMAND_NETWORK_USAGE = "cat /proc/net/dev";

    public static final Pattern CPU_PATTERN = Pattern.compile("%Cpu\\(s\\):\\s+([\\d.]+)\\s+us,\\s+([\\d.]+)\\s+sy,.*");
    public static final Pattern MEM_PATTERN = Pattern.compile("MiB Mem :\\s+([\\d.]+)\\s+total,\\s+([\\d.]+)\\s+free,\\s+([\\d.]+)\\s+used,.*");
    public static final Pattern NETWORK_PATTERN = Pattern.compile("^(eth|ens|enp|wlan)\\S*:"); // 주요 네트워크 인터페이스 패턴
}
