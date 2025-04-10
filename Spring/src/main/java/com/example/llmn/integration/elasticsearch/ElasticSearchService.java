package com.example.llmn.integration.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.json.JsonData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

import static com.example.llmn.integration.elasticsearch.ElasticSearchConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ElasticSearchService {

    private final ElasticsearchConfig elasticsearchConfig;

    public <T> SearchResponse<T> searchUnprocessedDocuments(String index, String elasticSearchHost, Class<T> responseType, int size) {
        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);
            SearchRequest searchRequest = buildUnprocessedDocsSearchRequest(index, size);
            return client.search(searchRequest, responseType);
        } catch (ElasticsearchException e) {
            createIndex(index, elasticSearchHost);
            return new SearchResponse.Builder<T>().build();
        } catch (java.net.ConnectException e) {
            log.error("<ElasticSearch> 연결 실패: {}", elasticSearchHost, e);
            return new SearchResponse.Builder<T>().build();
        } catch (IOException e) {
            log.error("<ElasticSearch> {} 인덱스 검색 실패", index, e);
            return new SearchResponse.Builder<T>().build();
        }
    }

    public <T> SearchResponse<T> searchDocumentsWithFilters(String index, Instant startTime, Instant endTime,
                                                            String logLevel, String containerName, String serverIp,
                                                            String elasticSearchHost, Class<T> responseType) {
        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);
            SearchRequest searchRequest = buildFilteredSearchRequest(index, startTime, endTime, logLevel, containerName, serverIp);
            return client.search(searchRequest, responseType);
        } catch (IOException e) {
            log.error("<ElasticSearch> {} 인덱스에 대한 필터 검색 실패", index, e);
            return new SearchResponse.Builder<T>().build();
        }
    }

    public <T> void updateDocuments(String index, List<Map<String, Object>> documents, String elasticSearchHost) {
        if (documents.isEmpty()) return;

        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);
            for (Map<String, Object> document : documents) {
                String documentId = (String) document.remove("_id");
                if (documentId == null) {
                    continue;
                }

                UpdateRequest<Map<String, Object>, Map<String, Object>> updateRequest = buildUpdateRequest(index, document, documentId);
                client.update(updateRequest, Map.class);
            }
        } catch (IOException e) {
            log.error("<ElasticSearch> {}에 대한 업데이트 실패", index, e);
        }
    }

    public void deleteDocumentsBefore(String index, Instant cutoffTime, String elasticSearchHost) {
        try {
            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);
            DeleteByQueryRequest deleteRequest = buildDeleteRequest(index, cutoffTime);
            client.deleteByQuery(deleteRequest);
        } catch (IOException e) {
            log.error("<ElasticSearch> {} 인덱스에서 오래된 문서 삭제 실패", index, e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Map<String, Object>> convertSearchResultToMap(SearchResponse<Map> searchResponse) {
        if (searchResponse.hits() == null || searchResponse.hits().hits() == null)
            return Collections.emptyList();

        return searchResponse
                .hits().hits().stream()
                .map(hit -> {
                    Map<String, Object> map = hit.source();
                    if (map != null) map.put(ES_FIELD_ID, hit.id());
                    return map;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private SearchRequest buildUnprocessedDocsSearchRequest(String index, int size) {
        return new SearchRequest.Builder()
                .index(index)
                .query(q -> q.bool(b -> b
                        .should(s -> s.term(t -> t.field(ES_FIELD_PROCESSED).value(false)))
                        .should(s -> s.bool(bs -> bs.mustNot(mn -> mn.exists(e -> e.field("is_processed")))))
                ))
                .size(size)
                .build();
    }

    private void createIndex(String indexName, String elasticSearchHost) {
        try {
            CreateIndexRequest createIndexRequest = new CreateIndexRequest.Builder()
                    .index(indexName)
                    .mappings(m -> m
                            .properties(ES_FIELD_PROCESSED, p -> p.boolean_(b -> b))
                            .properties(ES_FIELD_TIMESTAMP, p -> p.date(d -> d))
                            .properties(ES_FIELD_LEVEL, p -> p.keyword(k -> k))
                            .properties(ES_FIELD_CONTAINER_NAME, p -> p.keyword(k -> k))
                            .properties(ES_FIELD_SERVER_IP, p -> p.keyword(k -> k))
                            .properties(ES_FIELD_MESSAGE, p -> p.text(t -> t))
                    )
                    .build();

            ElasticsearchClient client = elasticsearchConfig.createElasticsearchClient(elasticSearchHost);
            client.indices().create(createIndexRequest);
        } catch (IOException e) {
            log.error("<ElasticSearch> {} 인덱스 생성 실패", indexName, e);
        }
    }

    private UpdateRequest<Map<String, Object>, Map<String, Object>> buildUpdateRequest(String index, Map<String, Object> document, String id) {
        return new UpdateRequest.Builder<Map<String, Object>, Map<String, Object>>()
                .index(index)
                .id(id)
                .doc(document)
                .build();
    }

    private SearchRequest buildFilteredSearchRequest(String index, Instant startTime, Instant endTime,
                                                     String logLevel, String containerName, String serverIp) {
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        // 시간 범위 필터 추가
        boolQuery.must(m -> m.range(r -> r
                .field(ES_FIELD_TIMESTAMP)
                .gte(JsonData.of(startTime.toString()))
                .lte(JsonData.of(endTime.toString()))
        ));

        // 선택적 필터 추가
        if (logLevel != null && !logLevel.isEmpty()) {
            boolQuery.filter(f -> f.term(t -> t.field(ES_FIELD_LEVEL).value(logLevel)));
        }
        if (containerName != null && !containerName.isEmpty()) {
            boolQuery.filter(f -> f.term(t -> t.field(ES_FIELD_CONTAINER_NAME).value(containerName)));
        }
        if (serverIp != null && !serverIp.isEmpty()) {
            boolQuery.filter(f -> f.term(t -> t.field(ES_FIELD_SERVER_IP).value(serverIp)));
        }

        return new SearchRequest.Builder()
                .index(index)
                .query(q -> q.bool(boolQuery.build()))
                .build();
    }

    private DeleteByQueryRequest buildDeleteRequest(String index, Instant cutoffTime) {
        return DeleteByQueryRequest.of(d -> d
                .index(index)
                .query(q -> q.range(r -> r
                        .field(ES_FIELD_TIMESTAMP)
                        .lte(JsonData.of(cutoffTime.toString()))
                ))
        );
    }
}
