package com.example.llmn.domain.metric;

import java.util.regex.Pattern;

public class MetricConstants {

    private MetricConstants() {}

    public static final int DEFAULT_METRIC_HISTORY_HOURS = 1;

    // 메트릭 키 이름 (맵에서 사용)
    public static final String KEY_CPU_USAGE = "cpuUsage";
    public static final String KEY_CPU_USER = "cpuUser";
    public static final String KEY_CPU_SYSTEM = "cpuSystem";
    public static final String KEY_CPU_NICE = "cpuNice";
    public static final String KEY_CPU_IDLE = "cpuIdle";
    public static final String KEY_CPU_IOWAIT = "cpuIowait";
    public static final String KEY_CPU_HI = "cpuHardwareInterrupt";
    public static final String KEY_CPU_SI = "cpuSoftwareInterrupt";
    public static final String KEY_CPU_STEAL = "cpuSteal";

    public static final String KEY_TOTAL_MEMORY = "totalMemory";
    public static final String KEY_USED_MEMORY = "usedMemory";
    public static final String KEY_FREE_MEMORY = "freeMemory";
    public static final String KEY_BUFFCACHE_MEMORY = "buffCacheMemory";
    public static final String KEY_AVAILABLE_MEMORY = "availableMemory";
    public static final String KEY_MEMORY_USAGE_PERCENT = "memoryUsagePercent";

    public static final String KEY_NETWORK_RECEIVED = "networkReceived";
    public static final String KEY_NETWORK_SENT = "networkSent";

    // 시스템 명령어
    public static final String CMD_CPU_MEMORY_STATS = "top -b -n1 | grep \"Cpu(s)\\|Mem\"";
    public static final String CMD_NETWORK_STATS = "cat /proc/net/dev";

    // 출력 파싱을 위한 정규식 패턴
    public static final Pattern PATTERN_CPU_LINE = Pattern.compile("%Cpu\\(s\\):\\s+([\\d.]+)\\s+us,\\s+([\\d.]+)\\s+sy,.*");
    public static final Pattern PATTERN_NETWORK_INTERFACE = Pattern.compile("^(eth|ens|enp|wlan)\\S*:"); // 주요 네트워크 인터페이스 패턴

    public static final Pattern PATTERN_MEMORY_LINE = Pattern.compile("MiB Mem :\\s+([\\d.]+)\\s+total,\\s+([\\d.]+)\\s+free,\\s+([\\d.]+)\\s+used,\\s+([\\d.]+)\\s+buff/cache.*");
    public static final Pattern PATTERN_SWAP_LINE = Pattern.compile("MiB Swap:.*([\\d.]+)\\s+avail Mem.*");

    public static final double DEFAULT_ZERO_VALUE = 0.0;
    public static final double BYTES_TO_MB_DIVISOR = 1024.0 * 1024.0;

    public static final int NETWORK_RECEIVED_BYTES_INDEX = 1;
    public static final int NETWORK_SENT_BYTES_INDEX = 9;
}