package com.example.llmn.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.example.llmn.controller.DTO.LogData;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LogDataService {

    private final ElasticsearchClient client;
    private Instant lastCollectedTime = Instant.now();

    @Scheduled(fixedRate = 60000)  // 1분마다 실행
    public void fetchLogs() throws IOException {
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
        Instant newLastCollectedTime = Instant.now();

        // 이전의 데이터 삭제
        //deleteOldLogs(lastCollectedTime);

        // 마지막 수집 시점 업데이트
        lastCollectedTime = newLastCollectedTime;

        // 검색된 로그를 처리
        String logs = processLogs(searchResponse);
        System.out.println("=========" + logs + "===============");
    }

    public List<LogData> searchLogs(Instant startTime, Instant endTime) throws IOException {
        // Elasticsearch 쿼리 생성
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                .index("docker-logs-*")  // 인덱스 패턴을 지정
                .query(q -> q.range(r -> r
                        .field("@timestamp")
                        .gte(JsonData.of(startTime.toString()))
                        .lte(JsonData.of(endTime.toString()))
                ));

        // Elasticsearch에서 쿼리 실행
        SearchResponse<LogData> response = client.search(searchBuilder.build(), LogData.class);

        // 검색 결과를 파싱하여 반환
        return response.hits().hits().stream()
                .map(Hit::source)
                .collect(Collectors.toList());
    }

    private String processLogs(SearchResponse searchResponse) {
        return searchResponse.toString();
    }

    private void deleteOldLogs(Instant lastCollectedTime) throws IOException {
        // Elasticsearch에서 lastCollectedTime 이전의 로그 삭제
        DeleteByQueryRequest deleteRequest = DeleteByQueryRequest.of(d -> d
                .index("docker-logs-*")
                .query(q -> q.range(r -> r
                        .field("@timestamp")
                        .lte(JsonData.of(lastCollectedTime.toString()))
                ))
        );

        // 삭제 요청 실행
        client.deleteByQuery(deleteRequest);
    }
}
