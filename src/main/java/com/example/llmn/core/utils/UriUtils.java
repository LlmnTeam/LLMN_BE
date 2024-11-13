package com.example.llmn.core.utils;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

public class UriUtils {

    public static URI buildURI(String uri) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(uri);
        return uriBuilder.build().encode().toUri();
    }
}
