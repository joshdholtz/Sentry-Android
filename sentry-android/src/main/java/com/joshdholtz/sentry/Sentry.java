package com.joshdholtz.sentry;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.UnknownHostException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Sentry {

    static final String TAG = "Sentry";
    private static final int MAX_QUEUE_LENGTH = 50;
    private static final String VERIFY_SSL = "verify_ssl";
    private static final String MEDIA_TYPE = "application/json; charset=utf-8";
    private static final int HTTP_OK = 200;
    private static final String API = "/api/";
    private static final String STORE = "/store/";
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 4;
    private static final String EVENT_ID = "event_id";
    private Context context;
    private String sentryVersion = "7";
    private String url;
    private String packageName;
    private final String publicKey;
    private final String secretKey;
    private int verifySsl;
    private SentryEventCaptureListener captureListener;
    private HttpRequestSender httpRequestSender;
    private ExecutorService executorService = fixedQueueDiscardingExecutor();
    private InternalStorage internalStorage;
    private Runnable sendUnsentRequests = new Runnable() {
        @Override
        public void run() {
            List<JSONObject> unsentRequests = internalStorage.getUnsentRequests();
            logD("Sending up " + unsentRequests.size() + " cached response(s)");
            for (JSONObject request : unsentRequests) {
                sendRequest(request);
            }
        }
    };
    private boolean enableDebugLogging;
    private SentryUncaughtExceptionHandler uncaughtExceptionHandler;
    private final Breadcrumbs breadcrumbs = new Breadcrumbs();

    private Sentry(Context applicationContext, String url, String publicKey,String secretKey, int verifySsl, String storageFileName, HttpRequestSender httpRequestSender) {
        context = applicationContext;
        this.url = url;
        this.publicKey = publicKey;
        this.secretKey = secretKey;
        this.verifySsl = verifySsl;
        this.httpRequestSender = httpRequestSender;
        packageName = applicationContext.getPackageName();
        internalStorage = new InternalStorage(storageFileName);
    }

    /**
     * This method returns new {@code Sentry} instance which can operate separately with other instances. Should be called once, usually in {@link Application#onCreate()} and obtained from some singleton. If you have only one instance than you can use {@link SentryInstance}. In {@link Activity#onCreate(Bundle)} you should call at least {@link #sendAllCachedCapturedEvents()} to try to send cached events
     *
     * @param context               this can be any context because {@link Context#getApplicationContext()} is used to avoid memory leak
     * @param dsnWithoutCredentials DSN of your project - remove credentials from it to avoid warning in Google Play console
     * @param httpRequestSender     used for sending events to Sentry server
     * @param storageFileName       unsent requests storage file - must be different for different instances
     * @param publicKey           public key from DSN
     * @param secretKey           secret key from DSN
     * @return new {@code Sentry} instance
     */
    public static Sentry newInstance(Context context, String dsnWithoutCredentials, HttpRequestSender httpRequestSender, String storageFileName, String publicKey,String secretKey) {
        Uri dsnUri = Uri.parse(dsnWithoutCredentials);

        Sentry sentry = new Sentry(context.getApplicationContext(), getUrl(dsnUri), publicKey, secretKey, getVerifySsl(dsnUri), storageFileName, httpRequestSender);
        sentry.setupUncaughtExceptionHandler();
        return sentry;
    }

    private static String getUrl(Uri uri) {
        String port = "";
        if (uri.getPort() >= 0) {
            port = ":" + uri.getPort();
        }

        String path = uri.getPath();

        String projectId = path.substring(path.lastIndexOf('/') + 1);
        return uri.getScheme() + "://" + uri.getHost() + port + API + projectId + STORE;
    }

    private static ExecutorService fixedQueueDiscardingExecutor() {
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
            new ArrayBlockingQueue<Runnable>(MAX_QUEUE_LENGTH),
            threadFactory, new ThreadPoolExecutor.DiscardPolicy()); // Discard exceptions
    }

    private static int getVerifySsl(Uri uri) {
        int verifySsl = 1;
        String queryParameter = uri.getQueryParameter(VERIFY_SSL);
        return queryParameter == null ? verifySsl : Integer.parseInt(queryParameter);
    }

    public void setSentryVersion(String sentryVersion) {
        this.sentryVersion = sentryVersion;
    }

    public void setupUncaughtExceptionHandler() {

        UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
        if (currentHandler != null) {
            logD("current handler class=" + currentHandler.getClass().getName());
            if (currentHandler == uncaughtExceptionHandler) {
                return;
            }
        }

        //disable existing handler because we don't know if someone wrapped us
        if (uncaughtExceptionHandler != null) {
            uncaughtExceptionHandler.disabled = true;
        }
        //register new handler
        uncaughtExceptionHandler = new SentryUncaughtExceptionHandler(currentHandler);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);

        sendAllCachedCapturedEvents();
    }

    private String createXSentryAuthHeader() {

        return "Sentry " +
            String.format("sentry_version=%s,", sentryVersion) +
            String.format("sentry_client=sentry-android/%s,", BuildConfig.SENTRY_ANDROID_VERSION) +
            String.format("sentry_key=%s,", publicKey) +
            String.format("sentry_secret=%s", secretKey);
    }


    public void sendAllCachedCapturedEvents() {
        if (shouldAttemptPost()) {
            submit(sendUnsentRequests);
        }
    }

    /**
     * @param captureListener the captureListener to set
     */
    public void setCaptureListener(SentryEventCaptureListener captureListener) {
        this.captureListener = captureListener;
    }

    public void captureMessage(String message) {
        captureMessage(message, SentryEventBuilder.SentryEventLevel.INFO);
    }

    public void captureMessage(String message, SentryEventBuilder.SentryEventLevel level) {
        captureEvent(newEventBuilder().setMessage(message).setLevel(level));
    }

    public SentryEventBuilder newEventBuilder() {
        return new SentryEventBuilder(enableDebugLogging);
    }

    public void captureException(Throwable t) {
        captureException(t, SentryEventBuilder.SentryEventLevel.ERROR);
    }

    public void captureException(Throwable t, SentryEventBuilder.SentryEventLevel level) {
        String culprit = getCause(t, t.getMessage());

        captureEvent(newEventBuilder().setMessage(t.getMessage()).setCulprit(culprit).setLevel(level).setException(t));
    }

    public void captureException(Throwable t, String message) {
        captureException(t, message, SentryEventBuilder.SentryEventLevel.ERROR);
    }

    public void captureException(Throwable t, String message, SentryEventBuilder.SentryEventLevel level) {
        String culprit = getCause(t, t.getMessage());

        captureEvent(newEventBuilder()
            .setMessage(message)
            .setCulprit(culprit)
            .setLevel(level)
            .setException(t)
        );

    }

    public void captureUncaughtException(Context context, Throwable t) {
        final Writer result = new StringWriter();
        final PrintWriter printWriter = new PrintWriter(result);
        t.printStackTrace(printWriter);
        try {
            // Random number to avoid duplicate files
            long random = System.currentTimeMillis();

            // Embed version in stacktrace filename
            File stacktrace = new File(getStacktraceLocation(context), "raven-" + random + ".stacktrace");
            logD("Writing unhandled exception to: " + stacktrace.getAbsolutePath());

            // Write the stacktrace to disk
            ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(stacktrace));
            oos.writeObject(t);
            oos.flush();
            // Close up everything
            oos.close();
        } catch (Exception ebos) {
            // Nothing much we can do about this - the game is over
            logW(ebos);
        }
        logD(result.toString());
    }

    private String getCause(Throwable t, String culprit) {
        for (StackTraceElement stackTrace : t.getStackTrace()) {
            if (stackTrace.toString().contains(packageName)) {
                return stackTrace.toString();
            }
        }

        return culprit;
    }

    private static File getStacktraceLocation(Context context) {
        return new File(context.getCacheDir(), "crashes");
    }

    public void captureEvent(SentryEventBuilder builder) {
        JSONObject request = createEvent(builder);


        if (enableDebugLogging) {
            //this string can be really big so there is no need to log if logging is disabled
            logD("Request - " + request);
        }

        doCaptureEventPost(request);
    }

    private JSONObject createEvent(SentryEventBuilder builder) {
        builder.setJsonArray("breadcrumbs", breadcrumbs.current());
        if (captureListener != null) {

            builder = captureListener.beforeCapture(builder);
            if (builder == null) {
                logW("SentryEventBuilder in captureEvent is null");
                return null;
            }
        }
        return createRequest(builder);
    }

    private boolean shouldAttemptPost() {
        PackageManager pm = context.getPackageManager();
        int hasPerm = pm.checkPermission(Manifest.permission.ACCESS_NETWORK_STATE, packageName);
        if (hasPerm == PackageManager.PERMISSION_DENIED) {
            return true;
        }

        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private void doCaptureEventPost(final JSONObject request) {

        if (shouldAttemptPost()) {
            submit(new Runnable() {
                @Override
                public void run() {
                    sendRequest(request);
                }
            });
        } else {
            submit(new Runnable() {
                @Override
                public void run() {
                    addRequest(request);
                }
            });
        }
    }

    private void submit(Runnable task) {
        executorService.submit(task);
    }

    private void addRequest(JSONObject request) {
        internalStorage.addRequest(request);
    }

    private void sendRequest(JSONObject request) {
        logD("Sending to URL - " + url);

        HttpRequestSender.Builder builder = httpRequestSender.newBuilder();
        if (verifySsl != 0) {
            logD("Using http client");
        } else {
            logD("Using https client");
            builder.useHttps();
        }

        builder.url(url);
        boolean success = false;
        try {
            builder.header("X-Sentry-Auth", createXSentryAuthHeader());
            builder.header("User-Agent", "sentry-android/" + BuildConfig.SENTRY_ANDROID_VERSION);
            builder.header("Content-Type", MEDIA_TYPE);

            builder.content(request.toString(), MEDIA_TYPE);
            HttpRequestSender.Response httpResponse = builder.build().execute();

            int status = httpResponse.getStatusCode();
            success = status == HTTP_OK;

            logD("SendEvent - " + status + " " + httpResponse.getContent());
        }catch (UnknownHostException unh) {
            //LogCat does not log UnknownHostException
           logW("UnknownHostException on sending event");
        }
        catch (Exception e) {
            logW(e);
        }

        if (success) {
            internalStorage.removeBuilder(request);
        } else {
            addRequest(request);
        }
    }

    public void setDebugLogging(boolean enableDebugLogging) {
        this.enableDebugLogging = enableDebugLogging;
    }

    private void logD(String message) {
        if (enableDebugLogging) {
            Log.d(TAG, message);
        }
    }

    private void logW(String message) {
        if (enableDebugLogging) {
            Log.w(TAG, message);
        }
    }

    private void logW(Exception ex) {
        if (enableDebugLogging) {
            Log.w(TAG, ex);
        }
    }

    private void logW(String message, Exception ex) {
        logW(message, ex, enableDebugLogging);
    }

    private static void logW(String message, Exception ex, boolean enableDebugLogging) {
        if (enableDebugLogging) {
            Log.w(TAG, message, ex);
        }
    }

    private class SentryUncaughtExceptionHandler implements UncaughtExceptionHandler {

        UncaughtExceptionHandler defaultExceptionHandler;
        boolean disabled;

        // constructor
        public SentryUncaughtExceptionHandler(UncaughtExceptionHandler pDefaultExceptionHandler) {
            defaultExceptionHandler = pDefaultExceptionHandler;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable e) {
            if (disabled) {
                return;
            }
            // Here you should have a more robust, permanent record of problems
            SentryEventBuilder builder = newEventBuilder(e, SentryEventBuilder.SentryEventLevel.FATAL);

            JSONObject event = createEvent(builder);
            if (event != null) {
                addRequest(event);
            } else {
                logW("SentryEventBuilder in uncaughtException is null");
            }

            //call original handler
            if (defaultExceptionHandler != null) {
                defaultExceptionHandler.uncaughtException(thread, e);
            }
        }
    }

    private static JSONObject createRequest(SentryEventBuilder builder) {
        return builder.toJson();
    }

    public SentryEventBuilder newEventBuilder(Throwable e, SentryEventBuilder.SentryEventLevel level) {
        return new SentryEventBuilder(e, level, getCause(e, e.getMessage()), enableDebugLogging);
    }

    private final class InternalStorage {

        private List<JSONObject> unsentRequests;
        private String fileName;

        private InternalStorage(String fileName) {
            this.fileName = fileName;
        }

        /**
         * @return the unsentRequests
         */
        public List<JSONObject> getUnsentRequests() {
            synchronized (this) {
                return new ArrayList<JSONObject>(lazyGetUnsentRequests());
            }
        }

        private List<JSONObject> lazyGetUnsentRequests() {
            if (unsentRequests == null) {
                unsentRequests = readRequests();
            }
            return unsentRequests;
        }

        public void addRequest(JSONObject request) {
            synchronized (this) {
                logD("Adding request - " + getEventId(request));
                if (!contains(request)) {
                    lazyGetUnsentRequests().add(request);
                    writeRequests();
                }
            }
        }

        private boolean contains(JSONObject request) {
            List<JSONObject> list = lazyGetUnsentRequests();
            for (JSONObject object : list) {
                if (getEventId(object).equals(getEventId(request))) {
                    return true;
                }
            }
            return false;
        }

        public void removeBuilder(JSONObject request) {
            synchronized (this) {
                logD("Removing request - " + getEventId(request));
                lazyGetUnsentRequests().remove(request);
                writeRequests();
            }
        }

        private void writeRequests() {
            OutputStream fos = null;
            try {

                JSONArray jsonArray = new JSONArray();
                Iterable<? extends JSONObject> requests = lazyGetUnsentRequests();
                for (JSONObject request : requests) {
                    jsonArray.put(request);
                }
                String s = jsonArray.toString();
                fos = context.openFileOutput(fileName, Context.MODE_PRIVATE);
                fos.write(s.getBytes("UTF-8"));
            } catch (IOException e) {
                logW(e);
            } finally {
                try {
                    if (fos != null) {
                        fos.close();
                    }
                } catch (IOException e) {
                    logW(e);
                }
            }
        }

        private List<JSONObject> readRequests() {
            Reader reader = null;
            try {
                StringWriter sw = new StringWriter();
                reader = new InputStreamReader(context.openFileInput(fileName));
                char[] buffer = new char[DEFAULT_BUFFER_SIZE];
                int n;
                while (-1 != (n = reader.read(buffer))) {
                    sw.write(buffer, 0, n);
                }
                JSONArray jsonArray = new JSONArray(sw.toString());
                List<JSONObject> requests = new ArrayList<>(jsonArray.length());
                for (int i = 0; i < jsonArray.length(); i++) {
                    requests.add(jsonArray.getJSONObject(i));
                }
                return requests;
            } catch (Exception e) {
                logW(e);
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        logW(e);
                    }
                }
            }
            return new ArrayList<JSONObject>();
        }
    }

    private static String getEventId(JSONObject object) {
        return object.optString(EVENT_ID);
    }

    public interface SentryEventCaptureListener {

        SentryEventBuilder beforeCapture(SentryEventBuilder builder);
    }


    /**
     * The Sentry server assumes the time is in UTC.
     * The timestamp should be in ISO 8601 format, without a timezone.
     */
    private static SimpleDateFormat iso8601() {
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }

    public static final class SentryEventBuilder {

        static final Pattern IS_INTERNAL_PACKAGE = Pattern.compile("^(java|android|com\\.android|com\\.google\\.android|dalvik\\.system)\\..*");
        private static final ThreadLocal<SimpleDateFormat> sdf = new ThreadLocal<SimpleDateFormat>() {
            @Override
            protected SimpleDateFormat initialValue() {
                return iso8601();
            }
        };
        private static final Pattern PATTERN = Pattern.compile("-", Pattern.LITERAL);
        private JSONObject event;

        // Convert a StackTraceElement to a sentry.interfaces.stacktrace.Stacktrace JSON object.
        static JSONObject frameJson(StackTraceElement ste) throws JSONException {
            final JSONObject frame = new JSONObject();

            final String method = ste.getMethodName();
            if (!TextUtils.isEmpty(method)) {
                frame.put("function", method);
            }

            final String fileName = ste.getFileName();
            if (!TextUtils.isEmpty(fileName)) {
                frame.put("filename", fileName);
            }

            int lineno = ste.getLineNumber();
            if (!ste.isNativeMethod() && lineno >= 0) {
                frame.put("lineno", lineno);
            }

            String className = ste.getClassName();
            frame.put("module", className);

            // Take out some of the system packages to improve the exception folding on the sentry server
            frame.put("in_app", !IS_INTERNAL_PACKAGE.matcher(className).matches());

            return frame;
        }

        public SentryEventBuilder setContexts(JSONObject contexts) {
            return setJsonObject("contexts", contexts);
        }

        public enum SentryEventLevel {

            FATAL("fatal"),
            ERROR("error"),
            WARNING("warning"),
            INFO("info"),
            DEBUG("debug");
            private String value;

            SentryEventLevel(String value) {
                this.value = value;
            }

        }

        private boolean enableLogging;

        SentryEventBuilder(boolean enableLogging) {
            this.enableLogging = enableLogging;
            event = new JSONObject();
            setString(EVENT_ID, PATTERN.matcher(UUID.randomUUID().toString()).replaceAll(Matcher.quoteReplacement("")));
            setString("platform", "java");
            setTimestamp(System.currentTimeMillis());
        }

        private SentryEventBuilder(Throwable t, SentryEventLevel level, String cause, boolean enableLogging) {
            this(enableLogging);

            setMessage(t.getMessage()).setCulprit(cause).setLevel(level).setException(t);
        }

        /**
         * "message": "SyntaxError: Wattttt!"
         *
         * @param message Message
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setMessage(String message) {
            return setString("message", message);
        }

        /**
         * "timestamp": "2011-05-02T17:41:36"
         *
         * @param timestamp Timestamp
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setTimestamp(long timestamp) {
            return setString("timestamp", sdf.get().format(new Date(timestamp)));
        }

        /**
         * "level": "warning"
         *
         * @param level Level
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setLevel(SentryEventLevel level) {
            return setString("level", level.value);
        }

        /**
         * "logger": "my.logger.name"
         *
         * @param logger Logger
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setLogger(String logger) {
            return setString("logger", logger);
        }

        /**
         * "culprit": "my.module.function_name"
         *
         * @param culprit Culprit
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setCulprit(String culprit) {
            return setString("culprit", culprit);
        }

        private SentryEventBuilder setString(String key, String culprit) {
            return putSafely(key, culprit);
        }

        private SentryEventBuilder putSafely(String key, Object object) {
            try {
                event.put(key, object);
            } catch (JSONException e) {
                //there should be no exception
                logW("", e, enableLogging);
            }
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
            return setJsonObject("user", user);
        }

        public JSONObject getUser() {
            if (!event.has("user")) {
                setTags(new JSONObject());
            }

            return (JSONObject) event.opt("user");
        }

        /**
         * @param tags Tags
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setTags(Map<String, String> tags) {
            setTags(new JSONObject(tags));
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

        public SentryEventBuilder addExtra(String key, String value) {
            try {
                getExtra().put(key, value);
            } catch (JSONException e) {
                Log.e(Sentry.TAG, "Error adding extra in SentryEventBuilder");
            }

            return this;
        }

        public SentryEventBuilder setTags(JSONObject tags) {
            return setJsonObject("tags", tags);
        }

        public SentryEventBuilder setJsonObject(String key, JSONObject object) {
           return putSafely(key, object);
        }

        public SentryEventBuilder setJsonArray(String key, JSONArray object) {
           return putSafely(key, object);
        }

        public JSONObject getTags() {
            if (!event.has("tags")) {
                setTags(new JSONObject());
            }

            return (JSONObject) event.opt("tags");
        }

        /**
         * @param serverName Server name
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setServerName(String serverName) {
            return setString("server_name", serverName);
        }

        /**
         * @param environment The environment name, such as production or staging
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setEnvironment(String environment) {
            return setString("environment", environment);
        }

        /**
         * @param release Release
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setRelease(String release) {
            return setString("release", release);
        }

        /**
         * @param name    Name
         * @param version Version
         * @return SentryEventBuilder
         */
        public SentryEventBuilder addModule(String name, String version) {
            JSONArray modules;
            try {

                if (!event.has("modules")) {
                    modules = new JSONArray();
                    event.put("modules", modules);
                } else {
                    modules = (JSONArray) event.get("modules");
                }

                if (name != null && version != null) {
                    String[] module = {name, version};
                    modules.put(new JSONArray(Arrays.asList(module)));
                }
            } catch (JSONException e) {
                logW("", e, enableLogging);
            }
            return this;
        }

        /**
         * @param extra Extra
         * @return SentryEventBuilder
         */
        public SentryEventBuilder setExtra(Map<String, String> extra) {
            return setExtra(new JSONObject(extra));
        }

        public SentryEventBuilder setExtra(JSONObject extra) {
            return setJsonObject("extra", extra);
        }

        public JSONObject getExtra() {
            if (!event.has("extra")) {
                setExtra(new JSONObject());
            }

            return (JSONObject) event.opt("extra");
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

        /**
         * Add a stack trace to the event.
         * A stack trace for the current thread can be obtained by using
         * `Thread.currentThread().getStackTrace()`.
         *
         * @param stackTrace stacktrace
         * @return same builder
         * @see Thread#currentThread()
         * @see Thread#getStackTrace()
         */
        public SentryEventBuilder setStackTrace(StackTraceElement[] stackTrace) {
            setJsonObject("stacktrace", getStackTrace(stackTrace));
            return this;
        }

        private static JSONObject getStackTrace(StackTraceElement[] stackFrames) {

            JSONObject stacktrace = new JSONObject();

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

        private JSONObject toJson() {
            return event;
        }
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
        final SentryEventBuilder.SentryEventLevel level;
        final Map<String, String> data = new HashMap<>();

        Breadcrumb(long timestamp, Type type, String message, String category, SentryEventBuilder.SentryEventLevel level) {
            this.timestamp = timestamp;
            this.type = type;
            this.message = message;
            this.category = category;
            this.level = level;
        }
    }

    private static class Breadcrumbs {

        // The max number of breadcrumbs that will be tracked at any one time.
        private static final int MAX_BREADCRUMBS = 10;


        // Access to this list must be thread-safe.
        // See GitHub Issue #110
        // This list is protected by the provided ReadWriteLock.
        private final LinkedList<Breadcrumb> breadcrumbs = new LinkedList<>();
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        void push(Breadcrumb b) {
            try {
                lock.writeLock().lock();
                while (breadcrumbs.size() >= MAX_BREADCRUMBS) {
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

    }

    /**
     * Record a breadcrumb to log a navigation from `from` to `to`.
     *
     * @param category A category to label the event under. This generally is similar to a logger
     *                 name, and will let you more easily understand the area an event took place, such as auth.
     * @param from     A string representing the original application state / location.
     * @param to       A string representing the new application state / location.
     * @see com.joshdholtz.sentry.Sentry#addHttpBreadcrumb(String, String, int)
     */
    public void addNavigationBreadcrumb(String category, String from, String to) {
        final Breadcrumb b = new Breadcrumb(
            System.currentTimeMillis() / 1000,
            Breadcrumb.Type.Navigation,
            "",
            category,
            SentryEventBuilder.SentryEventLevel.INFO);

        b.data.put("from", from);
        b.data.put("to", to);
        breadcrumbs.push(b);
    }

    /**
     * Record a HTTP request breadcrumb. This represents an HTTP request transmitted from your
     * application. This could be an AJAX request from a web application, or a server-to-server HTTP
     * request to an API service provider, etc.
     *
     * @param url        The request URL.
     * @param method     The HTTP request method.
     * @param statusCode The HTTP status code of the response.
     * @see com.joshdholtz.sentry.Sentry#addHttpBreadcrumb(String, String, int)
     */
    public void addHttpBreadcrumb(String url, String method, int statusCode) {
        final Breadcrumb b = new Breadcrumb(
            System.currentTimeMillis() / 1000,
            Breadcrumb.Type.HTTP,
            "",
            String.format("http.%s", method.toLowerCase()),
            SentryEventBuilder.SentryEventLevel.INFO);

        b.data.put("url", url);
        b.data.put("method", method);
        b.data.put("status_code", Integer.toString(statusCode));
        breadcrumbs.push(b);
    }

    /**
     * Sentry supports a concept called Breadcrumbs, which is a trail of events which happened prior
     * to an issue. Often times these events are very similar to traditional logs, but also have the
     * ability to record more rich structured data.
     *
     * @param category A category to label the event under. This generally is similar to a logger
     *                 name, and will let you more easily understand the area an event took place,
     *                 such as auth.
     * @param message  A string describing the event. The most common vector, often used as a drop-in
     *                 for a traditional log message.
     *                 <p>
     *                 See https://docs.sentry.io/hosted/learn/breadcrumbs/
     */
    public void addBreadcrumb(String category, String message) {
        breadcrumbs.push(new Breadcrumb(
            System.currentTimeMillis() / 1000,
            Breadcrumb.Type.Default,
            message,
            category,
            SentryEventBuilder.SentryEventLevel.INFO));
    }


}
