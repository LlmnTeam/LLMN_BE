package com.example.llm_ops.core.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // REST 클라이언트를 생성
        RestClient restClient = RestClient.builder(
                new HttpHost("localhost", 9200, "http")).build();

        // JSONP Mapper를 설정
        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());

        // ElasticsearchClient를 생성
        return new ElasticsearchClient(transport);
    }
}
