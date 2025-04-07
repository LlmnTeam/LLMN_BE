package com.example.llmn.integration.elasticsearch;

public class ElasticSearchConstants {

    private ElasticSearchConstants() {}

    // 기본 필드 그룹화
    public static final String ES_FIELD_TIMESTAMP = "@timestamp";
    public static final String ES_FIELD_LEVEL = "log_level";
    public static final String ES_FIELD_MESSAGE = "message";
    public static final String ES_FIELD_PROCESSED = "is_processed";
    public static final String ES_FIELD_ID = "_id";
    public static final String ES_FIELD_SERVER_IP = "server_ip";
    public static final String CONTAINER_NAME_FIELD = "name";

    // 컨테이너 관련 필드 그룹화
    public static final String ES_FIELD_CONTAINER_NAME = "container_name";
    public static final String ES_FIELD_CONTAINER_OBJECT = "container";

    public static final String UNKNOWN_CONTAINER = "unknown_container";
    public static final String UNKNOWN_SERVER_IP = "unknown";
}