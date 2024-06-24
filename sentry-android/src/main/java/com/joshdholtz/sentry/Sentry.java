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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.HttpURLConnection;
import java.net.URL;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static java.util.concurrent.TimeUnit.SECONDS;

public class Sentry {

    private static final String TAG = "Sentry";
    private final static String sentryVersion = "7";
    private static final int MAX_QUEUE_LENGTH = 50;

    public static boolean debug = false;

    private Context context;
    private String baseUrl;
    private Uri dsn;
    private AppInfo appInfo = AppInfo.Empty;
    private boolean verifySsl;
    private SentryEventCaptureListener captureListener;
    private JSONObject contexts = new JSONObject();
    private Executor executor;
    private boolean disableOnDebug;
    final Breadcrumbs breadcrumbs = new Breadcrumbs();

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

    static class LazyHolder {
        static final Sentry instance = new Sentry();
    }

    public static void init(Context context, String dsn, boolean disableOnDebug) {
        init(context, dsn, true, disableOnDebug);
    }

    public static void init(Context context, String dsn) {
        init(context, dsn, true, false);
    }

    public static void init(Context context, String dsn, boolean setupUncaughtExceptionHandler, boolean disableOnDebug) {
        final Sentry sentry = Sentry.getInstance();

        sentry.context = context.getApplicationContext();
        sentry.disableOnDebug = disableOnDebug && BuildConfig.DEBUG;

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
            60, SECONDS, // Kill unused threads after this length.
            new ArrayBlockingQueue<Runnable>(queueSize),
            threadFactory, new ThreadPoolExecutor.DiscardPolicy()); // Discard exceptions
    }

    private static boolean getVerifySsl(String dsn) {
        try {
            final Uri uri = Uri.parse(dsn);
            final String value = uri.getQueryParameter("verify_ssl");
            return value == null || Integer.parseInt(value) != 0;
        } catch (Exception e) {
            Log.w(TAG, "Could not parse verify_ssl correctly", e);
            return true;
        }
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
        return path.substring(path.lastIndexOf("/") + 1);
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

    /**
     * Set a limit on the number of breadcrumbs that will be stored by the client, and sent with
     * exceptions.
     *
     * @param maxBreadcrumbs the maximum number of breadcrumbs to store and send.
     */
    public static void setMaxBreadcrumbs(int maxBreadcrumbs) {
        getInstance().breadcrumbs.setMaxBreadcrumbs(maxBreadcrumbs);
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
        addDefaultRelease(builder, sentry.appInfo);
        builder.event.put("breadcrumbs", Sentry.getInstance().breadcrumbs.current());
        if (sentry.captureListener != null) {

            builder = sentry.captureListener.beforeCapture(builder);
            if (builder == null) {
                Log.e(Sentry.TAG, "SentryEventBuilder in captureEvent is null");
                return;
            }
        }

        request = new SentryEventRequest(builder);

        log("Request - " + request.requestData);

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

    private static void ignoreSslErrors(HttpURLConnection connection) {
        try {

            if (!(connection instanceof HttpsURLConnection)) {
                return;
            }

            final HttpsURLConnection https = (HttpsURLConnection) connection;

            final X509TrustManager x509TrustManager = new X509TrustManager() {
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

            https.setSSLSocketFactory(sslContext.getSocketFactory());

            https.setHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String host, SSLSession sess) {
                    return true;
                }
            });

        } catch (Exception ex) {
            Log.w(TAG, "Error bypassing SSL validation", ex);
        }
    }

    private Runnable makePoster(final SentryEventRequest request) {

        return new Runnable() {
            @Override
            public void run() {
                try {
                    int projectId = Integer.parseInt(getProjectId(dsn));
                    URL url = new URL(baseUrl + "/api/" + projectId + "/store/");

                    final HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                    if (!verifySsl) {
                        ignoreSslErrors(conn);
                    }

                    final int timeoutMillis = (int)SECONDS.toMillis(10);

                    conn.setConnectTimeout(timeoutMillis);
                    conn.setReadTimeout(timeoutMillis);
                    conn.setDoOutput(true);
                    conn.setDoInput(false);
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("X-Sentry-Auth", createXSentryAuthHeader(dsn));
                    conn.setRequestProperty("User-Agent", "sentry-android/" + BuildConfig.SENTRY_ANDROID_VERSION);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                    OutputStream os = conn.getOutputStream();
                    os.write(request.requestData.getBytes("UTF-8"));
                    os.close();

                    final int status = conn.getResponseCode();
                    final boolean success = status == 200;

                    conn.disconnect();

                    log("SendEvent status=" + status);

                    if (success) {
                        InternalStorage.getInstance().removeBuilder(request);
                    } else {
                        InternalStorage.getInstance().addRequest(request);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error sending event", e);
                }
            }
        };

    }


    private static void doCaptureEventPost(final SentryEventRequest request) {
        final Sentry sentry = Sentry.getInstance();

        if (!sentry.shouldAttemptPost()) {
            InternalStorage.getInstance().addRequest(request);
            return;
        }

        if (!sentry.disableOnDebug) {
            sentry.executor.execute(sentry.makePoster(request));
        }
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
            addDefaultRelease(builder, sentry.appInfo);
            builder.event.put("breadcrumbs", sentry.breadcrumbs.current());

            if (sentry.captureListener != null) {
                builder = sentry.captureListener.beforeCapture(builder);
            }

            if (builder != null) {
                builder.event.put("contexts", sentry.contexts);
                storage.addRequest(new SentryEventRequest(builder));
            } else {
                Log.e(Sentry.TAG, "SentryEventBuilder in uncaughtException is null");
            }

            // Call original handler
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

    static class Breadcrumbs {

        // The max number of breadcrumbs that will be tracked at any one time.
        final AtomicInteger maxBreadcrumbs = new AtomicInteger(100);

        // Access to this list must be thread-safe.
        // See GitHub Issue #110
        // This list is protected by the provided ReadWriteLock.
        final LinkedList<Breadcrumb> breadcrumbs = new LinkedList<>();
        final ReadWriteLock lock = new ReentrantReadWriteLock();

        void push(Breadcrumb b) {
            try {
                lock.writeLock().lock();

                final int toRemove = breadcrumbs.size() - maxBreadcrumbs.get() + 1;
                for (int i = 0; i < toRemove; i++) {
                    breadcrumbs.removeFirst();
                }
                breadcrumbs.add(b);
            } finally {
                lock.writeLock().unlock();
            }
        }

        JSONArray current() {
            final JSONArray crumbs = new JSONArray();
            try {
                lock.readLock().lock();
                for (Breadcrumb breadcrumb : breadcrumbs) {
                    final JSONObject json = new JSONObject();
                    json.put("timestamp", breadcrumb.timestamp);
                    json.put("type", breadcrumb.type.value);
                    json.put("message", breadcrumb.message);
                    json.put("category", breadcrumb.category);
                    json.put("level", breadcrumb.level.value);
                    json.put("data", new JSONObject(breadcrumb.data));
                    crumbs.put(json);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error serializing breadcrumbs", e);
            } finally {
                lock.readLock().unlock();
            }
            return crumbs;
        }

        void setMaxBreadcrumbs(int maxBreadcrumbs) {
            maxBreadcrumbs = Math.min(200, Math.max(0, maxBreadcrumbs));
            this.maxBreadcrumbs.set(maxBreadcrumbs);
        }

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
        getInstance().breadcrumbs.push(b);
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
        final String reason = httpReason(statusCode);
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
        getInstance().breadcrumbs.push(b);
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
        getInstance().breadcrumbs.push(new Breadcrumb(
            System.currentTimeMillis() / 1000,
            Breadcrumb.Type.Default,
            message,
            category,
            SentryEventLevel.INFO));
    }

    private static class SentryEventRequest implements Serializable {
        final String requestData;
        final UUID uuid;

        SentryEventRequest(SentryEventBuilder builder) {
            this.requestData = new JSONObject(builder.event).toString();
            this.uuid = UUID.randomUUID();
        }

        @Override
        public boolean equals(Object other) {
            final boolean sameClass = other instanceof SentryEventRequest;
            return sameClass && uuid == ((SentryEventRequest) other).uuid;
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

        final Map<String, Object> event;

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
         * "environment": "dev"
         *
         * @param env Environment
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setEnvironment(String env) {
            event.put("environment", env);
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
    final static class AppInfo {
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
            os.put("build", Integer.toString(Build.VERSION.SDK_INT));
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
     * Map from HTTP status code to reason description.
     * Sentry HTTP breadcrumbs expect a text description of the HTTP status-code.
     * This function implements a look-up table with a default value for unknown status-codes.
     * @param statusCode an integer HTTP status code, expected to be in the range [200,505].
     * @return a non-empty string in all cases.
     */
    private static String httpReason(int statusCode) {
        switch (statusCode) {
            // 2xx
            case HttpURLConnection.HTTP_OK: return "OK";
            case HttpURLConnection.HTTP_CREATED: return "Created";
            case HttpURLConnection.HTTP_ACCEPTED: return "Accepted";
            case HttpURLConnection.HTTP_NOT_AUTHORITATIVE: return "Non-Authoritative Information";
            case HttpURLConnection.HTTP_NO_CONTENT: return "No Content";
            case HttpURLConnection.HTTP_RESET: return "Reset Content";
            case HttpURLConnection.HTTP_PARTIAL: return "Partial Content";

            // 3xx
            case HttpURLConnection.HTTP_MULT_CHOICE: return "Multiple Choices";
            case HttpURLConnection.HTTP_MOVED_PERM: return "Moved Permanently";
            case HttpURLConnection.HTTP_MOVED_TEMP: return "Temporary Redirect";
            case HttpURLConnection.HTTP_SEE_OTHER: return "See Other";
            case HttpURLConnection.HTTP_NOT_MODIFIED: return "Not Modified";
            case HttpURLConnection.HTTP_USE_PROXY: return "Use Proxy";

            // 4xx
            case HttpURLConnection.HTTP_BAD_REQUEST: return "Bad Request";
            case HttpURLConnection.HTTP_UNAUTHORIZED: return "Unauthorized";
            case HttpURLConnection.HTTP_PAYMENT_REQUIRED: return "Payment Required";
            case HttpURLConnection.HTTP_FORBIDDEN: return "Forbidden";
            case HttpURLConnection.HTTP_NOT_FOUND: return "Not Found";
            case HttpURLConnection.HTTP_BAD_METHOD: return "Method Not Allowed";
            case HttpURLConnection.HTTP_NOT_ACCEPTABLE: return "Not Acceptable";
            case HttpURLConnection.HTTP_PROXY_AUTH: return "Proxy Authentication Required";
            case HttpURLConnection.HTTP_CLIENT_TIMEOUT: return "Request Time-Out";
            case HttpURLConnection.HTTP_CONFLICT: return "Conflict";
            case HttpURLConnection.HTTP_GONE: return "Gone";
            case HttpURLConnection.HTTP_LENGTH_REQUIRED: return "Length Required";
            case HttpURLConnection.HTTP_PRECON_FAILED: return "Precondition Failed";
            case HttpURLConnection.HTTP_ENTITY_TOO_LARGE: return "Request Entity Too Large";
            case HttpURLConnection.HTTP_REQ_TOO_LONG: return "Request-URI Too Large";
            case HttpURLConnection.HTTP_UNSUPPORTED_TYPE: return "Unsupported Media Type";

            // 5xx
            case HttpURLConnection.HTTP_INTERNAL_ERROR: return "Internal Server Error";
            case HttpURLConnection.HTTP_NOT_IMPLEMENTED: return "Not Implemented";
            case HttpURLConnection.HTTP_BAD_GATEWAY: return "Bad Gateway";
            case HttpURLConnection.HTTP_UNAVAILABLE: return "Service Unavailable";
            case HttpURLConnection.HTTP_GATEWAY_TIMEOUT: return "Gateway Timeout";
            case HttpURLConnection.HTTP_VERSION: return "Version Not Supported";

            default: return "unknown";
        }
    }

    /**
     * Take the idea of `present?` from ActiveSupport.
     */
    private static boolean Present(String s) {
        return s != null && s.length() > 0;
    }

    static void addDefaultRelease(SentryEventBuilder event, AppInfo appInfo) {
        if (event.event.containsKey("release")) {
            return;
        }
        event.setRelease(appInfo.versionName);
    }
}
