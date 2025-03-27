package com.example.llmn.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    private static final int CONNECT_TIMEOUT = 100_000; // 100초
    private static final int READ_WRITE_TIMEOUT = 60; // 60초
    private static final int BUFFER_SIZE_MB = 1024 * 1024; // 1MB

    @Bean
    public WebClient webClient(ClientHttpConnector connector, ExchangeStrategies exchangeStrategies) {
        return WebClient.builder()
                .exchangeStrategies(exchangeStrategies)
                .clientConnector(connector)
                .build();
    }

    @Bean
    public ClientHttpConnector clientHttpConnector(HttpClient httpClient) {
        return new ReactorClientHttpConnector(httpClient);
    }

    @Bean
    public HttpClient httpClient(ConnectionProvider connectionProvider, LoopResources loopResources) {
        return HttpClient.create(connectionProvider)
                .runOn(loopResources)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT)
                .doOnConnected(connection ->
                        connection.addHandlerLast(new ReadTimeoutHandler(READ_WRITE_TIMEOUT))
                                .addHandlerLast(new WriteTimeoutHandler(READ_WRITE_TIMEOUT)))
                .responseTimeout(Duration.ofSeconds(60));
    }

    @Bean(destroyMethod = "dispose")
    public ConnectionProvider connectionProvider() {
        return ConnectionProvider.create("customConnectionProvider");
    }

    @Bean(destroyMethod = "dispose")
    public LoopResources loopResources() {
        return LoopResources.create("cuomLoopResources");
    }

    @Bean
    public ExchangeStrategies exchangeStrategies() {
        return ExchangeStrategies.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(BUFFER_SIZE_MB))
                .build();
    }
}