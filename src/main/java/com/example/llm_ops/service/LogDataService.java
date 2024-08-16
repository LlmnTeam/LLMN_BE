package com.example.llm_ops.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class LogDataService {

    private final ElasticsearchClient client;
    private Instant lastCollectedTime = Instant.now();  // 처음 실행 시 현재 시간을 기준으로 설정

    @Scheduled(fixedRate = 60000)  // 1분마다 실행
    public void fetchLogsAndSendToFastAPI() throws IOException {
        // Elasticsearch에서 마지막 수집 시점 이후의 로그만 검색
        SearchRequest searchRequest = SearchRequest.of(s -> s
                .index("docker-logs-*")
                .query(q -> q.range(r -> r
                        .field("@timestamp")
                        .gt(JsonData.of(lastCollectedTime.toString()))
                ))
        );

        SearchResponse<Object> searchResponse = client.search(searchRequest, Object.class);

        // 새로운 마지막 수집 시점을 업데이트
        lastCollectedTime = Instant.now();

        // 검색된 로그를 처리
        String logs = processLogs(searchResponse);
        System.out.println("=========" + logs + "===============");
    }

    private String processLogs(SearchResponse searchResponse) {

        return searchResponse.toString();
    }
}
