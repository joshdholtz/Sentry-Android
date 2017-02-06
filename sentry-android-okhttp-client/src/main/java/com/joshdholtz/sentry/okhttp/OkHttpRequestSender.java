package com.joshdholtz.sentry.okhttp;

import com.joshdholtz.sentry.HttpRequestSender;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public class OkHttpRequestSender implements HttpRequestSender {

    private static class OkBuilder implements Builder {

        private static class OkResponse implements Response {

            private final int code;
            private final String bodyContent;

            public OkResponse(int code, String bodyContent) {
                this.code = code;
                this.bodyContent = bodyContent;
            }

            @Override
            public int getStatusCode() {
                return code;
            }

            @Override
            public String getContent() {
                return bodyContent;
            }
        }

        private final okhttp3.Request.Builder builder;
        private OkHttpClient okHttpClient;
        private boolean useHttps;
        private String url;

        private OkBuilder(OkHttpClient okHttpClient) {
            this.okHttpClient = okHttpClient;
            builder = new okhttp3.Request.Builder();
        }

        @Override
        public Request build() throws Exception {
            final Call call = okHttpClient.newCall(builder.build());
            return new Request() {
                @Override
                public Response execute() throws Exception {
                    okhttp3.Response response = call.execute();
                    try {
                        ResponseBody body = response.body();
                        String bodyContent = null;
                        if (body != null) {
                            bodyContent = body.string();
                        }

                        return new OkResponse(response.code(), bodyContent);
                    } finally {
                        if (response.body() != null) {
                            response.body().close();
                        }
                    }
                }
            };
        }

        @Override
        public Builder useHttps() {
            useHttps = true;
            if (url != null) {
                addHttps();
            }
            return this;
        }

        private void addHttps() {
            if (!url.startsWith("http")) {
                url = "https://" + url;
            }
        }

        @Override
        public Builder url(String url) {
            this.url = url;
            if (useHttps) {
                addHttps();
            }
            builder.url(this.url);
            return this;
        }

        @Override
        public Builder header(String headerName, String headerValue) {
            builder.header(headerName, headerValue);
            return this;
        }

        @Override
        public Builder content(String requestData, String mediaType) {
            builder.post(RequestBody.create(MediaType.parse(mediaType), requestData));
            return this;
        }
    }

    private OkHttpClient okHttpClient;

    public OkHttpRequestSender(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    @Override
    public Builder newBuilder() {
        return new OkBuilder(okHttpClient);
    }
}
