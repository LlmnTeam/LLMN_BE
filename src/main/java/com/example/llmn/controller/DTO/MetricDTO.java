package com.example.llmn.controller.DTO;

public class MetricDTO {

    public record NetworkTraffic(long bytesReceived, long bytesSent) {}
}
