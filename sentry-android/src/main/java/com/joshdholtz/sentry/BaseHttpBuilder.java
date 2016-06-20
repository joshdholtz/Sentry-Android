package com.joshdholtz.sentry;

import java.util.HashMap;
import java.util.Map;

public abstract class BaseHttpBuilder implements HttpRequestSender.Builder {
    private String url;
    private Map<String, String> headers = new HashMap<>();
    private String requestData;
    private String mediaType;
    private boolean useHttps;

    @Override
    public HttpRequestSender.Request build() throws Exception {
        return build(url, headers, requestData, mediaType, useHttps);
    }

    protected abstract HttpRequestSender.Request build(String url, Map<String, String> headers, String requestData, String mediaType, boolean useHttps) throws Exception;

    @Override
    public HttpRequestSender.Builder useHttps() {
        useHttps = true;
        return this;
    }

    @Override
    public HttpRequestSender.Builder url(String url) {
        this.url = url;
        return this;
    }

    @Override
    public HttpRequestSender.Builder header(String headerName, String headerValue) {
        headers.put(headerName, headerValue);
        return this;
    }

    @Override
    public HttpRequestSender.Builder content(String requestData, String mediaType) {
        this.requestData = requestData;
        this.mediaType = mediaType;
        return this;
    }
}
