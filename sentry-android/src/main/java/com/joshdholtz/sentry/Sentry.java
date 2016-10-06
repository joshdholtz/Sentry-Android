package com.joshdholtz.sentry;

import android.Manifest.permission;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.EnglishReasonPhraseCatalog;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class Sentry {

    private static final String TAG = "Sentry";
    private final static String sentryVersion = "7";
    private static final int MAX_QUEUE_LENGTH = 50;
    private static final int MAX_BREADCRUMBS = 10;

    public static boolean debug = false;

    private Context context;
    private String baseUrl;
    private Uri dsn;
    private AppInfo appInfo = AppInfo.Empty;
    private boolean verifySsl;
    private SentryEventCaptureListener captureListener;
    private JSONObject contexts = new JSONObject();
    private Executor executor;
    private LinkedList<Breadcrumb> breadcrumbs = new LinkedList<>();

    public enum SentryEventLevel {

        FATAL("fatal"),
        ERROR("error"),
        WARNING("warning"),
        INFO("info"),
        DEBUG("debug");

        private final String value;

        SentryEventLevel(String value) {
            this.value = value;
        }
    }

    private Sentry() {
    }

    private static void log(String text) {
        if (debug) {
            Log.d(TAG, text);
        }
    }

    private static Sentry getInstance() {
        return LazyHolder.instance;
    }

    private static class LazyHolder {
        private static final Sentry instance = new Sentry();
    }

    public static void init(Context context, String dsn) {
        init(context, dsn, true);
    }

    public static void init(Context context, String dsn, boolean setupUncaughtExceptionHandler) {
        final Sentry sentry = Sentry.getInstance();

        sentry.context = context.getApplicationContext();

        Uri uri = Uri.parse(dsn);
        String port = "";
        if (uri.getPort() >= 0) {
            port = ":" + uri.getPort();
        }

        sentry.baseUrl = uri.getScheme() + "://" + uri.getHost() + port;
        sentry.dsn = uri;
        sentry.appInfo = AppInfo.Read(sentry.context);
        sentry.verifySsl = getVerifySsl(dsn);
        sentry.contexts = readContexts(sentry.context, sentry.appInfo);
        sentry.executor = fixedQueueDiscardingExecutor(MAX_QUEUE_LENGTH);

        if (setupUncaughtExceptionHandler) {
            sentry.setupUncaughtExceptionHandler();
        }
    }

    private static Executor fixedQueueDiscardingExecutor(int queueSize) {
        // Name our threads so that it is easy for app developers to see who is creating threads.
        final ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicLong count = new AtomicLong();

            @Override
            public Thread newThread(Runnable runnable) {
                final Thread thread = new Thread(runnable);
                thread.setName(String.format(Locale.US, "Sentry HTTP Thread %d", count.incrementAndGet()));
                return thread;
            }
        };

        return new ThreadPoolExecutor(
            0, 1, // Keep 0 threads alive. Max pool size is 1.
            60, TimeUnit.SECONDS, // Kill unused threads after this length.
            new ArrayBlockingQueue<Runnable>(queueSize),
            threadFactory, new ThreadPoolExecutor.DiscardPolicy()); // Discard exceptions
    }

    private static boolean getVerifySsl(String dsn) {
        List<NameValuePair> params = getAllGetParams(dsn);
        for (NameValuePair param : params) {
            if (param.getName().equals("verify_ssl"))
                return Integer.parseInt(param.getValue()) != 0;
        }
        return false;
    }

    private static List<NameValuePair> getAllGetParams(String dsn) {
        List<NameValuePair> params = null;
        try {
            params = URLEncodedUtils.parse(new URI(dsn), HTTP.UTF_8);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return params;
    }

    private void setupUncaughtExceptionHandler() {

        UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
        if (currentHandler != null) {
            log("current handler class=" + currentHandler.getClass().getName());
        }

        // don't register again if already registered
        if (!(currentHandler instanceof SentryUncaughtExceptionHandler)) {
            // Register default exceptions handler
            Thread.setDefaultUncaughtExceptionHandler(
                new SentryUncaughtExceptionHandler(currentHandler, InternalStorage.getInstance()));
        }

        sendAllCachedCapturedEvents();
    }

    private static String createXSentryAuthHeader(Uri dsn) {

        final StringBuilder header = new StringBuilder();

        final String authority = dsn.getAuthority().replace("@" + dsn.getHost(), "");

        final String[] authorityParts = authority.split(":");
        final String publicKey = authorityParts[0];
        final String secretKey = authorityParts[1];

        header.append("Sentry ")
            .append(String.format("sentry_version=%s,", sentryVersion))
            .append(String.format("sentry_client=sentry-android/%s,", BuildConfig.SENTRY_ANDROID_VERSION))
            .append(String.format("sentry_key=%s,", publicKey))
            .append(String.format("sentry_secret=%s", secretKey));

        return header.toString();
    }

    private static String getProjectId(Uri dsn) {
        String path = dsn.getPath();
        String projectId = path.substring(path.lastIndexOf("/") + 1);

        return projectId;
    }

    public static void sendAllCachedCapturedEvents() {
        List<SentryEventRequest> unsentRequests = InternalStorage.getInstance().getUnsentRequests();
        log("Sending up " + unsentRequests.size() + " cached response(s)");
        for (SentryEventRequest request : unsentRequests) {
            Sentry.doCaptureEventPost(request);
        }
    }

    /**
     * @param captureListener the captureListener to set
     */
    public static void setCaptureListener(SentryEventCaptureListener captureListener) {
        Sentry.getInstance().captureListener = captureListener;
    }

    public static void captureMessage(String message) {
        Sentry.captureMessage(message, SentryEventLevel.INFO);
    }

    public static void captureMessage(String message, SentryEventLevel level) {
        Sentry.captureEvent(new SentryEventBuilder()
            .setMessage(message)
            .setLevel(level)
        );
    }

    public static void captureException(Throwable t) {
        Sentry.captureException(t, t.getMessage(), SentryEventLevel.ERROR);
    }

    public static void captureException(Throwable t, String message) {
        Sentry.captureException(t, message, SentryEventLevel.ERROR);
    }

    public static void captureException(Throwable t, SentryEventLevel level) {
        captureException(t, t.getMessage(), level);
    }

    public static void captureException(Throwable t, String message, SentryEventLevel level) {
        String culprit = getCause(t, t.getMessage());

        Sentry.captureEvent(new SentryEventBuilder()
            .setMessage(message)
            .setCulprit(culprit)
            .setLevel(level)
            .setException(t)
        );

    }

    private static String getCause(Throwable t, String culprit) {

        final String packageName = Sentry.getInstance().appInfo.name;

        for (StackTraceElement stackTrace : t.getStackTrace()) {
            if (stackTrace.toString().contains(packageName)) {
                return stackTrace.toString();
            }
        }

        return culprit;
    }

    public static void captureEvent(SentryEventBuilder builder) {
        final Sentry sentry = Sentry.getInstance();
        final SentryEventRequest request;
        builder.event.put("contexts", sentry.contexts);
        builder.setRelease(Integer.toString(sentry.appInfo.versionCode));
        builder.event.put("breadcrumbs", Sentry.getInstance().currentBreadcrumbs());
        if (sentry.captureListener != null) {


            builder = sentry.captureListener.beforeCapture(builder);
            if (builder == null) {
                Log.e(Sentry.TAG, "SentryEventBuilder in captureEvent is null");
                return;
            }

            request = new SentryEventRequest(builder);
        } else {
            request = new SentryEventRequest(builder);
        }

        log("Request - " + request.getRequestData());

        doCaptureEventPost(request);
    }

    private boolean shouldAttemptPost() {
        PackageManager pm = context.getPackageManager();
        int hasPerm = pm.checkPermission(permission.ACCESS_NETWORK_STATE, context.getPackageName());
        if (hasPerm != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private static class ExSSLSocketFactory extends SSLSocketFactory {
        SSLContext sslContext = SSLContext.getInstance("TLS");

        ExSSLSocketFactory(SSLContext context) throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException {
            super(null);
            sslContext = context;
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return sslContext.getSocketFactory().createSocket(socket, host, port, autoClose);
        }

        @Override
        public Socket createSocket() throws IOException {
            return sslContext.getSocketFactory().createSocket();
        }
    }

    private static HttpClient getHttpsClient(HttpClient client) {
        try {
            X509TrustManager x509TrustManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain,
                                               String authType) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain,
                                               String authType) throws CertificateException {
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
            ClientConnectionManager clientConnectionManager = client.getConnectionManager();
            SchemeRegistry schemeRegistry = clientConnectionManager.getSchemeRegistry();
            schemeRegistry.register(new Scheme("https", sslSocketFactory, 443));
            return new DefaultHttpClient(clientConnectionManager, client.getParams());
        } catch (Exception ex) {
            return null;
        }
    }

    private static void doCaptureEventPost(final SentryEventRequest request) {
        final Sentry sentry = Sentry.getInstance();

        if (!sentry.shouldAttemptPost()) {
            InternalStorage.getInstance().addRequest(request);
            return;
        }

        sentry.executor.execute(new Runnable() {
            @Override
            public void run() {
                int projectId = Integer.parseInt(getProjectId(sentry.dsn));
                String url = sentry.baseUrl + "/api/" + projectId + "/store/";

                log("Sending to URL - " + url);

                HttpClient httpClient;
                if (Sentry.getInstance().verifySsl) {
                    log("Using http client");
                    httpClient = new DefaultHttpClient();
                } else {
                    log("Using https client");
                    httpClient = getHttpsClient(new DefaultHttpClient());
                }

                HttpPost httpPost = new HttpPost(url);

                int TIMEOUT_MILLISEC = 10000;  // = 20 seconds
                HttpParams httpParams = httpPost.getParams();
                HttpConnectionParams.setConnectionTimeout(httpParams, TIMEOUT_MILLISEC);
                HttpConnectionParams.setSoTimeout(httpParams, TIMEOUT_MILLISEC);

                HttpProtocolParams.setContentCharset(httpParams, HTTP.UTF_8);
                HttpProtocolParams.setHttpElementCharset(httpParams, HTTP.UTF_8);

                boolean success = false;
                try {
                    httpPost.setHeader("X-Sentry-Auth", createXSentryAuthHeader(sentry.dsn));
                    httpPost.setHeader("User-Agent", "sentry-android/" + BuildConfig.SENTRY_ANDROID_VERSION);
                    httpPost.setHeader("Content-Type", "application/json; charset=utf-8");

                    httpPost.setEntity(new StringEntity(request.getRequestData(), HTTP.UTF_8));
                    HttpResponse httpResponse = httpClient.execute(httpPost);

                    int status = httpResponse.getStatusLine().getStatusCode();
                    byte[] byteResp = null;

                    // Gets the input stream and unpackages the response into a command
                    if (httpResponse.getEntity() != null) {
                        try {
                            InputStream in = httpResponse.getEntity().getContent();
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

                    success = (status == 200);

                    log("SendEvent - " + status + " " + stringResponse);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (success) {
                    InternalStorage.getInstance().removeBuilder(request);
                } else {
                    InternalStorage.getInstance().addRequest(request);
                }
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

        });


    }

    private static class SentryUncaughtExceptionHandler implements UncaughtExceptionHandler {

        private final InternalStorage storage;
        private final UncaughtExceptionHandler defaultExceptionHandler;

        // constructor
        public SentryUncaughtExceptionHandler(UncaughtExceptionHandler pDefaultExceptionHandler, InternalStorage storage) {
            defaultExceptionHandler = pDefaultExceptionHandler;
            this.storage = storage;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable e) {

            final Sentry sentry = Sentry.getInstance();

            // Here you should have a more robust, permanent record of problems
            SentryEventBuilder builder = new SentryEventBuilder(e, SentryEventLevel.FATAL);
            builder.setRelease(Integer.toString(sentry.appInfo.versionCode));

            if (sentry.captureListener != null) {
                builder = sentry.captureListener.beforeCapture(builder);
            }

            if (builder != null) {
                builder.event.put("contexts", sentry.contexts);
                storage.addRequest(new SentryEventRequest(builder));
            } else {
                Log.e(Sentry.TAG, "SentryEventBuilder in uncaughtException is null");
            }

            //call original handler
            defaultExceptionHandler.uncaughtException(thread, e);
        }

    }

    private static class InternalStorage {

        private final static String FILE_NAME = "unsent_requests";
        private final List<SentryEventRequest> unsentRequests;

        private static InternalStorage getInstance() {
            return LazyHolder.instance;
        }

        private static class LazyHolder {
            private static final InternalStorage instance = new InternalStorage();
        }

        private InternalStorage() {
            Context context = Sentry.getInstance().context;
            try {
                File unsetRequestsFile = new File(context.getFilesDir(), FILE_NAME);
                if (!unsetRequestsFile.exists()) {
                    writeObject(context, new ArrayList<Sentry.SentryEventRequest>());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error initializing storage", e);
            }
            this.unsentRequests = this.readObject(context);
        }

        /**
         * @return the unsentRequests
         */
        public List<SentryEventRequest> getUnsentRequests() {
            final List<SentryEventRequest> copy = new ArrayList<>();
            synchronized (this) {
                copy.addAll(unsentRequests);
            }
            return copy;
        }

        public void addRequest(SentryEventRequest request) {
            synchronized (this) {
                log("Adding request - " + request.uuid);
                if (!this.unsentRequests.contains(request)) {
                    this.unsentRequests.add(request);
                    this.writeObject(Sentry.getInstance().context, this.unsentRequests);
                }
            }
        }

        public void removeBuilder(SentryEventRequest request) {
            synchronized (this) {
                log("Removing request - " + request.uuid);
                this.unsentRequests.remove(request);
                this.writeObject(Sentry.getInstance().context, this.unsentRequests);
            }
        }

        private void writeObject(Context context, List<SentryEventRequest> requests) {
            try {
                FileOutputStream fos = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE);
                ObjectOutputStream oos = new ObjectOutputStream(fos);
                oos.writeObject(requests);
                oos.close();
                fos.close();
            } catch (IOException e) {
                Log.e(TAG, "Error saving to storage", e);
            }
        }

        private List<SentryEventRequest> readObject(Context context) {
            try {
                FileInputStream fis = context.openFileInput(FILE_NAME);
                ObjectInputStream ois = new ObjectInputStream(fis);
                List<SentryEventRequest> requests = (ArrayList<SentryEventRequest>) ois.readObject();
                ois.close();
                fis.close();
                return requests;
            } catch (IOException | ClassNotFoundException e) {
                Log.e(TAG, "Error loading from storage", e);
            }
            return new ArrayList<>();
        }
    }

    public interface SentryEventCaptureListener {

        SentryEventBuilder beforeCapture(SentryEventBuilder builder);

    }

    private final static class Breadcrumb {

        enum Type {

            Default("default"),
            HTTP("http"),
            Navigation("navigation");

            private final String value;

            Type(String value) {
                this.value = value;
            }
        }

        final long timestamp;
        final Type type;
        final String message;
        final String category;
        final SentryEventLevel level;
        final Map<String, String> data = new HashMap<>();

        Breadcrumb(long timestamp, Type type, String message, String category, SentryEventLevel level) {
            this.timestamp = timestamp;
            this.type = type;
            this.message = message;
            this.category = category;
            this.level = level;
        }
    }

    private void pushBreadcrumb(Breadcrumb b) {
        while (breadcrumbs.size() >= MAX_BREADCRUMBS) {
            breadcrumbs.removeFirst();
        }
        breadcrumbs.add(b);
    }

    /**
     * Record a breadcrumb to log a navigation from `from` to `to`.
     * @param category A category to label the event under. This generally is similar to a logger
     *                 name, and will let you more easily understand the area an event took place, such as auth.
     * @param from A string representing the original application state / location.
     * @param to A string representing the new application state / location.
     *
     * @see com.joshdholtz.sentry.Sentry#addHttpBreadcrumb(String, String, int)
     */
    public static void addNavigationBreadcrumb(String category, String from, String to) {
        final Breadcrumb b = new Breadcrumb(
            System.currentTimeMillis() / 1000,
            Breadcrumb.Type.Navigation,
            "",
            category,
            SentryEventLevel.INFO);

        b.data.put("from", from);
        b.data.put("to", to);
        getInstance().pushBreadcrumb(b);
    }

    /**
     * Record a HTTP request breadcrumb. This represents an HTTP request transmitted from your
     * application. This could be an AJAX request from a web application, or a server-to-server HTTP
     * request to an API service provider, etc.
     *
     * @param url The request URL.
     * @param method The HTTP request method.
     * @param statusCode The HTTP status code of the response.
     *
     * @see com.joshdholtz.sentry.Sentry#addHttpBreadcrumb(String, String, int)
     */
    public static void addHttpBreadcrumb(String url, String method, int statusCode) {
        final String reason = EnglishReasonPhraseCatalog.INSTANCE.getReason(statusCode, Locale.US);
        final Breadcrumb b = new Breadcrumb(
            System.currentTimeMillis() / 1000,
            Breadcrumb.Type.HTTP,
            "",
            String.format("http.%s", method.toLowerCase()),
            SentryEventLevel.INFO);

        b.data.put("url", url);
        b.data.put("method", method);
        b.data.put("status_code", Integer.toString(statusCode));
        b.data.put("reason", reason);
        getInstance().pushBreadcrumb(b);
    }

    /**
     * Sentry supports a concept called Breadcrumbs, which is a trail of events which happened prior
     * to an issue. Often times these events are very similar to traditional logs, but also have the
     * ability to record more rich structured data.
     *
     * @param category A category to label the event under. This generally is similar to a logger
     *                 name, and will let you more easily understand the area an event took place,
     *                 such as auth.
     *
     * @param message A string describing the event. The most common vector, often used as a drop-in
     *                for a traditional log message.
     *
     * See https://docs.sentry.io/hosted/learn/breadcrumbs/
     *
     */
    public static void addBreadcrumb(String category, String message) {
        getInstance().pushBreadcrumb(new Breadcrumb(
            System.currentTimeMillis() / 1000,
            Breadcrumb.Type.Default,
            message,
            category,
            SentryEventLevel.INFO));
    }

    private JSONArray currentBreadcrumbs() {
        final JSONArray crumbs = new JSONArray();
        for (Breadcrumb breadcrumb : breadcrumbs) {
            final JSONObject json = new JSONObject();
            try {
                json.put("timestamp", breadcrumb.timestamp);
                json.put("type", breadcrumb.type.value);
                json.put("message", breadcrumb.message);
                json.put("category", breadcrumb.category);
                json.put("level", breadcrumb.level.value);
                json.put("data", new JSONObject(breadcrumb.data));
            } catch (JSONException jse) {
                Log.e(TAG, "Error serializing breadcrumbs", jse);
            }
            crumbs.put(json);
        }
        return crumbs;
    }

    public static class SentryEventRequest implements Serializable {
        private final String requestData;
        private final UUID uuid;

        public SentryEventRequest(SentryEventBuilder builder) {
            this.requestData = new JSONObject(builder.event).toString();
            this.uuid = UUID.randomUUID();
        }

        /**
         * @return the requestData
         */
        public String getRequestData() {
            return requestData;
        }

        /**
         * @return the uuid
         */
        public UUID getUuid() {
            return uuid;
        }

        @Override
        public boolean equals(Object other) {
            SentryEventRequest otherRequest = (SentryEventRequest) other;

            if (this.uuid != null && otherRequest.uuid != null) {
                return uuid.equals(otherRequest.uuid);
            }

            return false;
        }

    }

    /**
     * The Sentry server assumes the time is in UTC.
     * The timestamp should be in ISO 8601 format, without a timezone.
     */
    private static DateFormat iso8601() {
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }

    public static class SentryEventBuilder implements Serializable {

        private static final long serialVersionUID = -8589756678369463988L;

        // Match packages names that start with some well-known internal class-paths:
        // java.*
        // android.*
        // com.android.*
        // com.google.android.*
        // dalvik.system.*
        static final String isInternalPackage = "^(java|android|com\\.android|com\\.google\\.android|dalvik\\.system)\\..*";

        private final static DateFormat timestampFormat = iso8601();

        private final Map<String, Object> event;

        public JSONObject toJSON() {
            return new JSONObject(event);
        }

        public SentryEventBuilder() {
            event = new HashMap<>();
            event.put("event_id", UUID.randomUUID().toString().replace("-", ""));
            event.put("platform", "java");
            this.setTimestamp(System.currentTimeMillis());
        }

        public SentryEventBuilder(Throwable t, SentryEventLevel level) {
            this();

            String culprit = getCause(t, t.getMessage());

            this.setMessage(t.getMessage())
                .setCulprit(culprit)
                .setLevel(level)
                .setException(t);
        }

        /**
         * "message": "SyntaxError: Wattttt!"
         *
         * @param message Message
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setMessage(String message) {
            event.put("message", message);
            return this;
        }

        /**
         * "timestamp": "2011-05-02T17:41:36"
         *
         * @param timestamp Timestamp
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setTimestamp(long timestamp) {
            event.put("timestamp", timestampFormat.format(new Date(timestamp)));
            return this;
        }

        /**
         * "level": "warning"
         *
         * @param level Level
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setLevel(SentryEventLevel level) {
            event.put("level", level.value);
            return this;
        }

        /**
         * "logger": "my.logger.name"
         *
         * @param logger Logger
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setLogger(String logger) {
            event.put("logger", logger);
            return this;
        }

        /**
         * "culprit": "my.module.function_name"
         *
         * @param culprit Culprit
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setCulprit(String culprit) {
            event.put("culprit", culprit);
            return this;
        }

        /**
         * @param user User
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setUser(Map<String, String> user) {
            setUser(new JSONObject(user));
            return this;
        }

        public SentryEventBuilder setUser(JSONObject user) {
            event.put("user", user);
            return this;
        }

        public JSONObject getUser() {
            if (!event.containsKey("user")) {
                setTags(new HashMap<String, String>());
            }

            return (JSONObject) event.get("user");
        }

        /**
         * @param tags Tags
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setTags(Map<String, String> tags) {
            setTags(new JSONObject(tags));
            return this;
        }

        public SentryEventBuilder setTags(JSONObject tags) {
            event.put("tags", tags);
            return this;
        }

        public SentryEventBuilder addTag(String key, String value) {
            try {
                getTags().put(key, value);
            } catch (JSONException e) {
                Log.e(Sentry.TAG, "Error adding tag in SentryEventBuilder");
            }

            return this;
        }

        public JSONObject getTags() {
            if (!event.containsKey("tags")) {
                setTags(new HashMap<String, String>());
            }

            return (JSONObject) event.get("tags");
        }

        /**
         * @param serverName Server name
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setServerName(String serverName) {
            event.put("server_name", serverName);
            return this;
        }

        /**
         * @param release Release
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setRelease(String release) {
            event.put("release", release);
            return this;
        }

        /**
         * @param name    Name
         * @param version Version
         * @return SentryEventBuilder
         */
        public SentryEventBuilder addModule(String name, String version) {
            JSONArray modules;
            if (!event.containsKey("modules")) {
                modules = new JSONArray();
                event.put("modules", modules);
            } else {
                modules = (JSONArray) event.get("modules");
            }

            if (name != null && version != null) {
                String[] module = {name, version};
                modules.put(new JSONArray(Arrays.asList(module)));
            }

            return this;
        }

        /**
         * @param extra Extra
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setExtra(Map<String, String> extra) {
            setExtra(new JSONObject(extra));
            return this;
        }

        public SentryEventBuilder setExtra(JSONObject extra) {
            event.put("extra", extra);
            return this;
        }

        public SentryEventBuilder addExtra(String key, String value) {
            try {
                getExtra().put(key, value);
            } catch (JSONException e) {
                Log.e(Sentry.TAG, "Error adding extra in SentryEventBuilder");
            }

            return this;
        }

        public JSONObject getExtra() {
            if (!event.containsKey("extra")) {
                setExtra(new HashMap<String, String>());
            }

            return (JSONObject) event.get("extra");
        }

        /**
         * @param t Throwable
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setException(Throwable t) {
            JSONArray values = new JSONArray();

            while (t != null) {
                JSONObject exception = new JSONObject();

                try {
                    exception.put("type", t.getClass().getSimpleName());
                    exception.put("value", t.getMessage());
                    exception.put("module", t.getClass().getPackage().getName());
                    exception.put("stacktrace", getStackTrace(t.getStackTrace()));

                    values.put(exception);
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to build sentry report for " + t, e);
                }

                t = t.getCause();
            }

            JSONObject exceptionReport = new JSONObject();

            try {
                exceptionReport.put("values", values);
                event.put("exception", exceptionReport);
            } catch (JSONException e) {
                Log.e(TAG, "Unable to attach exception to event " + values, e);
            }

            return this;
        }

        private static JSONObject getStackTrace(StackTraceElement[] stackFrames) {

            JSONObject stacktrace  = new JSONObject();

            try {
                JSONArray frameList = new JSONArray();

                // Java stack frames are in the opposite order from what the Sentry client API expects.
                // > The zeroth element of the array (assuming the array's length is non-zero)
                // > represents the top of the stack, which is the last method invocation in the
                // > sequence.
                // See:
                // https://docs.oracle.com/javase/7/docs/api/java/lang/Throwable.html#getStackTrace()
                // https://docs.sentry.io/clientdev/interfaces/#failure-interfaces
                //
                // This code uses array indices rather a foreach construct since there is no built-in
                // reverse iterator in the Java standard library. To use a foreach loop would require
                // calling Collections.reverse which would require copying the array to a list.
                for (int i = stackFrames.length - 1; i >= 0; i--) {
                    frameList.put(frameJson(stackFrames[i]));
                }

                stacktrace.put("frames", frameList);
            } catch (JSONException e) {
                Log.e(TAG, "Error serializing stack frames", e);
            }

            return stacktrace;
        }

        /**
         * Add a stack trace to the event.
         * A stack trace for the current thread can be obtained by using
         * `Thread.currentThread().getStackTrace()`.
         *
         * @see Thread#currentThread()
         * @see Thread#getStackTrace()
         */
        public SentryEventBuilder setStackTrace(StackTraceElement[] stackTrace) {
            this.event.put("stacktrace", getStackTrace(stackTrace));
            return this;
        }

        // Convert a StackTraceElement to a sentry.interfaces.stacktrace.Stacktrace JSON object.
        static JSONObject frameJson(StackTraceElement ste) throws JSONException {
            final JSONObject frame = new JSONObject();

            final String method = ste.getMethodName();
            if (Present(method)) {
                frame.put("function", method);
            }

            final String fileName = ste.getFileName();
            if (Present(fileName)) {
                frame.put("filename", fileName);
            }

            int lineno = ste.getLineNumber();
            if (!ste.isNativeMethod() && lineno >= 0) {
                frame.put("lineno", lineno);
            }

            String className = ste.getClassName();
            frame.put("module", className);

            // Take out some of the system packages to improve the exception folding on the sentry server
            frame.put("in_app", !className.matches(isInternalPackage));

            return frame;
        }
    }

    /**
     * Store a tuple of package version information captured from PackageInfo
     *
     * @see PackageInfo
     */
    private final static class AppInfo {
        final static AppInfo Empty = new AppInfo("", "", 0);
        final String name;
        final String versionName;
        final int versionCode;

        AppInfo(String name, String versionName, int versionCode) {
            this.name = name;
            this.versionName = versionName;
            this.versionCode = versionCode;
        }

        static AppInfo Read(final Context context) {
            try {
                final PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                return new AppInfo(info.packageName, info.versionName, info.versionCode);
            } catch (Exception e) {
                Log.e(TAG, "Error reading package context", e);
                return Empty;
            }
        }
    }


    private static JSONObject readContexts(Context context, AppInfo appInfo) {
        final JSONObject contexts = new JSONObject();
        try {
            contexts.put("os", osContext());
            contexts.put("device", deviceContext(context));
            contexts.put("package", packageContext(appInfo));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build device contexts", e);
        }
        return contexts;
    }

    /**
     * Read the device and build into a map.
     * <p>
     * Not implemented:
     * -  battery_level
     * If the device has a battery this can be an integer defining the battery level (in
     * the range 0-100). (Android requires registration of an intent to query the battery).
     * - name
     * The name of the device. This is typically a hostname.
     * <p>
     * See https://docs.getsentry.com/hosted/clientdev/interfaces/#context-types
     */
    private static JSONObject deviceContext(Context context) {
        final JSONObject device = new JSONObject();
        try {
            // The family of the device. This is normally the common part of model names across
            // generations. For instance iPhone would be a reasonable family, so would be Samsung Galaxy.
            device.put("family", Build.BRAND);

            // The model name. This for instance can be Samsung Galaxy S3.
            device.put("model", Build.PRODUCT);

            // An internal hardware revision to identify the device exactly.
            device.put("model_id", Build.MODEL);

            final String architecture = System.getProperty("os.arch");
            if (Present(architecture)) {
                device.put("arch", architecture);
            }

            final int orient = context.getResources().getConfiguration().orientation;
            device.put("orientation", orient == Configuration.ORIENTATION_LANDSCAPE ?
                "landscape" : "portrait");

            // Read screen resolution in the format "800x600"
            // Normalised to have wider side first.
            final Object windowManager = context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null && windowManager instanceof WindowManager) {
                final DisplayMetrics metrics = new DisplayMetrics();
                ((WindowManager) windowManager).getDefaultDisplay().getMetrics(metrics);
                device.put("screen_resolution",
                    String.format("%sx%s",
                        Math.max(metrics.widthPixels, metrics.heightPixels),
                        Math.min(metrics.widthPixels, metrics.heightPixels)));
            }

        } catch (Exception e) {
            Log.e(TAG, "Error reading device context", e);
        }
        return device;
    }

    private static JSONObject osContext() {
        final JSONObject os = new JSONObject();
        try {
            os.put("type", "os");
            os.put("name", "Android");
            os.put("version", Build.VERSION.RELEASE);
            if (Build.VERSION.SDK_INT < 4) {
                os.put("build", Build.VERSION.SDK);
            } else {
                os.put("build", Integer.toString(Build.VERSION.SDK_INT));
            }
            final String kernelVersion = System.getProperty("os.version");
            if (Present(kernelVersion)) {
                os.put("kernel_version", kernelVersion);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error reading OS context", e);
        }
        return os;
    }

    /**
     * Read the package data into map to be sent as an event context item.
     * This is not a built-in context type.
     */
    private static JSONObject packageContext(AppInfo appInfo) {
        final JSONObject pack = new JSONObject();
        try {
            pack.put("type", "package");
            pack.put("name", appInfo.name);
            pack.put("version_name", appInfo.versionName);
            pack.put("version_code", Integer.toString(appInfo.versionCode));
        } catch (JSONException e) {
            Log.e(TAG, "Error reading package context", e);
        }
        return pack;
    }

    /**
     * Take the idea of `present?` from ActiveSupport.
     */
    private static boolean Present(String s) {
        return s != null && s.length() > 0;
    }
}
