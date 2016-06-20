package com.joshdholtz.sentry;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Sentry {

    private static final String TAG = "Sentry";
    private static final String VERSION = "0.2.0";
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
    private Uri dsn;
    private String packageName;
    private int verifySsl;
    private SentryEventCaptureListener captureListener;
    private HttpRequestSender httpRequestSender;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
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

    private Sentry(Context applicationContext, Uri dsnUri, String url, int verifySsl, String storageFileName, HttpRequestSender httpRequestSender) {
        context = applicationContext;
        dsn = dsnUri;
        this.url = url;
        this.verifySsl = verifySsl;
        this.httpRequestSender = httpRequestSender;
        packageName = applicationContext.getPackageName();
        internalStorage = new InternalStorage(storageFileName);
    }

    /**
     * This method returns new {@code Sentry} instance which can operate separately with other instances. Should be called once, usually in {@link Application#onCreate()} and obtained from some singleton. If you have only one instance than you can use {@link SentryInstance}. In {@link android.app.Activity#onCreate(Bundle)} you should call at least {@link #sendAllCachedCapturedEvents()} to try to send cached events
     *
     * @param context           this can be any context because {@link Context#getApplicationContext()} is used to avoid memory leak
     * @param dsn               DSN of your project
     * @param httpRequestSender used for sending events to Sentry server
     * @param storageFileName   unsent requests storage file - must be different for different instances
     * @return new {@code Sentry} instance
     */
    public static Sentry newInstance(Context context, String dsn, HttpRequestSender httpRequestSender, String storageFileName) {
        Uri dsnUri = Uri.parse(dsn);

        Sentry sentry = new Sentry(context.getApplicationContext(), dsnUri, getUrl(dsnUri), getVerifySsl(dsnUri), storageFileName, httpRequestSender);
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
        String header = "";

        String authority = dsn.getAuthority().replace("@" + dsn.getHost(), "");

        String[] authorityParts = authority.split(":");
        String publicKey = authorityParts[0];
        String secretKey = authorityParts[1];

        header += "Sentry sentry_version=" + sentryVersion + ",";
        header += "sentry_client=sentry-android/" + VERSION + ",";
        header += "sentry_timestamp=" + System.currentTimeMillis() + ",";
        header += "sentry_key=" + publicKey + ",";
        header += "sentry_secret=" + secretKey;

        return header;
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
        JSONObject request;
        if (captureListener != null) {

            builder = captureListener.beforeCapture(builder);
            if (builder == null) {
                logW("SentryEventBuilder in captureEvent is null");
                return;
            }

            request = createRequest(builder);
        } else {
            request = createRequest(builder);
        }

        if (enableDebugLogging) {
            //this string can be really big so there is no need to log if logging is disabled
            logD("Request - " + request);
        }

        doCaptureEventPost(request);
    }

    private boolean shouldAttemptPost() {
        PackageManager pm = context.getPackageManager();
        int hasPerm = pm.checkPermission(android.Manifest.permission.ACCESS_NETWORK_STATE, packageName);
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
            builder.header("User-Agent", "sentry-android/" + VERSION);
            builder.header("Content-Type", MEDIA_TYPE);

            builder.content(request.toString(), MEDIA_TYPE);
            HttpRequestSender.Response httpResponse = builder.build().execute();

            int status = httpResponse.getStatusCode();
            success = status == HTTP_OK;

            logD("SendEvent - " + status + " " + httpResponse.getContent());
        } catch (Exception e) {
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
            if (captureListener != null) {
                builder = captureListener.beforeCapture(builder);
            }

            if (builder != null) {
                addRequest(createRequest(builder));
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

    public static final class SentryEventBuilder {

        private static final ThreadLocal<SimpleDateFormat> sdf = new ThreadLocal<SimpleDateFormat>() {
            @Override
            protected SimpleDateFormat initialValue() {
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("GMT"));
                return format;
            }
        };
        private JSONObject event;

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

        private SentryEventBuilder(boolean enableLogging) {
            this.enableLogging = enableLogging;
            event = new JSONObject();
            setString(EVENT_ID, UUID.randomUUID().toString().replace("-", ""));
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
            try {
                event.put(key, culprit);
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

        public SentryEventBuilder setTags(JSONObject tags) {
            return setJsonObject("tags", tags);
        }

        private SentryEventBuilder setJsonObject(String key, JSONObject object) {
            try {
                event.put(key, object);
            } catch (JSONException e) {
                //there should be no exception
                logW("", e, enableLogging);
            }
            return this;
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
                    exception.put("stacktrace", getStackTrace(t));

                    values.put(exception);
                } catch (JSONException e) {
                    logW("Failed to build sentry report for " + t, e, enableLogging);
                }

                t = t.getCause();
            }

            JSONObject exceptionReport = new JSONObject();

            try {
                exceptionReport.put("values", values);
                event.put("exception", exceptionReport);
            } catch (JSONException e) {
                logW("Unable to attach exception to event " + values, e, enableLogging);
            }

            return this;
        }

        public static JSONObject getStackTrace(Throwable t) throws JSONException {
            JSONArray frameList = new JSONArray();

            for (StackTraceElement ste : t.getStackTrace()) {
                JSONObject frame = new JSONObject();

                String method = ste.getMethodName();
                if (method.length() != 0) {
                    frame.put("function", method);
                }

                int lineno = ste.getLineNumber();
                if (!ste.isNativeMethod() && lineno >= 0) {
                    frame.put("lineno", lineno);
                }

                boolean inApp = true;

                String className = ste.getClassName();
                frame.put("module", className);

                // Take out some of the system packages to improve the exception folding on the sentry server
                if (className.startsWith("android.") || className.startsWith("java.") || className.startsWith("dalvik.") || className.startsWith("com.android.")) {

                    inApp = false;
                }

                frame.put("in_app", inApp);

                frameList.put(frame);
            }

            JSONObject frameHash = new JSONObject();
            frameHash.put("frames", frameList);

            return frameHash;
        }

        private JSONObject toJson() {
            return event;
        }
    }
}
