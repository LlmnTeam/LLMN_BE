package com.example.llmn.core.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    @Value("${elasticsearch.host}")
    private String ELASTIC_SEARCH_HOST;

    private static final String HTTP_PROTOCOL = "http";

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // REST 클라이언트를 생성
        RestClient restClient = RestClient.builder(
                new HttpHost(ELASTIC_SEARCH_HOST, 9200, HTTP_PROTOCOL)).build();

        // JSONP Mapper를 설정
        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());

        // ElasticsearchClient를 생성
        return new ElasticsearchClient(transport);
    }
}
