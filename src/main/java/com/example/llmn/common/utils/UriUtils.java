package com.example.llmn.common.utils;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

public class UriUtils {

    private UriUtils() {}

    public static URI buildURI(String uri) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(uri);
        return uriBuilder.build().encode().toUri();
    }
}
