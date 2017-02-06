package com.joshdholtz.sentry.http.apache;

import com.joshdholtz.sentry.BaseHttpBuilder;
import com.joshdholtz.sentry.HttpRequestSender;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class ApacheHttpRequestSender implements HttpRequestSender {

    private static final int TIMEOUT = 10000;
    private static final String UTF_8 = "utf-8";

    @Override
    public Builder newBuilder() {
        return new ApacheBuilder();
    }

    static class ApacheBuilder extends BaseHttpBuilder {

        private static class ApacheResponse implements Response {

            private final HttpResponse response;

            public ApacheResponse(HttpResponse response) {
                this.response = response;
            }

            @Override
            public int getStatusCode() {
                return response.getStatusLine().getStatusCode();
            }

            @Override
            public String getContent() {
                // Gets the input stream and unpackages the response into a command
                byte[] byteResp = null;
                if (response.getEntity() != null) {
                    try {
                        InputStream in = response.getEntity().getContent();
                        byteResp = this.readBytes(in);

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                String stringResponse = null;
                Charset charsetInput = Charset.forName("UTF-8");
                CharsetDecoder decoder = charsetInput.newDecoder();
                CharBuffer cbuf = null;
                try {
                    cbuf = decoder.decode(ByteBuffer.wrap(byteResp));
                    stringResponse = cbuf.toString();
                } catch (CharacterCodingException e) {
                    e.printStackTrace();
                }
                return stringResponse;

            }

            private byte[] readBytes(InputStream inputStream) throws IOException {
                // this dynamically extends to take the bytes you read
                ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();

                // this is storage overwritten on each iteration with bytes
                int bufferSize = 1024;
                byte[] buffer = new byte[bufferSize];

                // we need to know how may bytes were read to write them to the byteBuffer
                int len = 0;
                while ((len = inputStream.read(buffer)) != -1) {
                    byteBuffer.write(buffer, 0, len);
                }

                // and then we can return your byte array.
                return byteBuffer.toByteArray();
            }
        }

        @Override
        protected Request build(String url, Map<String, String> headers, String requestData, String mediaType, boolean useHttps) throws Exception {
            final HttpClient client;
            if (useHttps) {
                client = getHttpsClient();
            } else {
                client = new DefaultHttpClient();
            }
            final HttpPost httpPost = new HttpPost(url);

            HttpParams httpParams = httpPost.getParams();
            HttpConnectionParams.setConnectionTimeout(httpParams, TIMEOUT);
            HttpConnectionParams.setSoTimeout(httpParams, TIMEOUT);

            HttpProtocolParams.setContentCharset(httpParams, UTF_8);
            HttpProtocolParams.setHttpElementCharset(httpParams, UTF_8);

            for (Map.Entry<String, String> header : headers.entrySet()) {
                httpPost.setHeader(header.getKey(), header.getValue());
            }

            httpPost.setEntity(new StringEntity(requestData, UTF_8));

            return new Request() {
                @Override
                public Response execute() throws Exception {
                    final HttpResponse response = client.execute(httpPost);
                    return new ApacheResponse(response);
                }
            };
        }

        private static class ExSSLSocketFactory extends SSLSocketFactory {

            SSLContext sslContext = SSLContext.getInstance("TLS");

            public ExSSLSocketFactory(KeyStore truststore) throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException {
                super(truststore);
                TrustManager x509TrustManager = new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                };

                sslContext.init(null, new TrustManager[]{x509TrustManager}, null);
            }

            public ExSSLSocketFactory(SSLContext context) throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException {
                super(null);
                sslContext = context;
            }

            @Override
            public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException, UnknownHostException {
                return sslContext.getSocketFactory().createSocket(socket, host, port, autoClose);
            }

            @Override
            public Socket createSocket() throws IOException {
                return sslContext.getSocketFactory().createSocket();
            }
        }

        public static HttpClient getHttpsClient() {
            DefaultHttpClient defaultHttpClient = new DefaultHttpClient();
            try {
                X509TrustManager x509TrustManager = new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                };

                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{x509TrustManager}, null);
                SSLSocketFactory sslSocketFactory = new ExSSLSocketFactory(sslContext);
                sslSocketFactory.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
                ClientConnectionManager clientConnectionManager = defaultHttpClient.getConnectionManager();
                SchemeRegistry schemeRegistry = clientConnectionManager.getSchemeRegistry();
                schemeRegistry.register(new Scheme("https", sslSocketFactory, 443));
                return new DefaultHttpClient(clientConnectionManager, defaultHttpClient.getParams());
            } catch (Exception ex) {
                return defaultHttpClient;
            }
        }
    }
}
