package com.joshdholtz.sentry;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.util.Log;

import com.joshdholtz.sentry.Sentry.SentryEventBuilder.SentryEventLevel;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class Sentry {

    private final static String VERSION = "0.1.2";
    private static final String TAG = "Sentry";
    private static final String DEFAULT_BASE_URL = "https://app.getsentry.com";
    private Context context;
    private String baseUrl;
    private String dsn;
    private String packageName;
    private int verifySsl;
    private SentryEventCaptureListener captureListener;

    private Sentry() {

    }

    private static Sentry getInstance() {
        return LazyHolder.instance;
    }

    public static void init(Context context, String dsn) {
        Sentry.init(context, DEFAULT_BASE_URL, dsn);
    }

    public static void init(Context context, String baseUrl, String dsn) {
        Sentry.getInstance().context = context;

        Sentry.getInstance().baseUrl = baseUrl;
        Sentry.getInstance().dsn = dsn;
        Sentry.getInstance().packageName = context.getPackageName();
        Sentry.getInstance().verifySsl = getVerifySsl(dsn);


        Sentry.getInstance().setupUncaughtExceptionHandler();
    }

    private static int getVerifySsl(String dsn) {
        int verifySsl = 1;
        List<NameValuePair> params = getAllGetParams(dsn);
        for (NameValuePair param : params) {
            if (param.getName().equals("verify_ssl"))
                return Integer.parseInt(param.getValue());
        }
        return verifySsl;
    }

    private static List<NameValuePair> getAllGetParams(String dsn) {
        List<NameValuePair> params = null;
        try {
            params = URLEncodedUtils.parse(new URI(dsn), "UTF-8");
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return params;
    }

    private static String createXSentryAuthHeader() {
        String header = "";

        Uri uri = Uri.parse(Sentry.getInstance().dsn);
        Log.d("Sentry", "URI - " + uri);
        String authority = uri.getAuthority().replace("@" + uri.getHost(), "");

        String[] authorityParts = authority.split(":");
        String publicKey = authorityParts[0];
        String secretKey = authorityParts[1];

        header += "Sentry sentry_version=4,";
        header += "sentry_client=sentry-android/" + VERSION + ",";
        header += "sentry_timestamp=" + System.currentTimeMillis() + ",";
        header += "sentry_key=" + publicKey + ",";
        header += "sentry_secret=" + secretKey;

        return header;
    }

    private static String getProjectId() {
        Uri uri = Uri.parse(Sentry.getInstance().dsn);
        String path = uri.getPath();
        String projectId = path.substring(path.lastIndexOf("/") + 1);

        return projectId;
    }

    public static void sendAllCachedCapturedEvents() {
        ArrayList<SentryEventRequest> unsentRequests = InternalStorage.getInstance().getUnsentRequests();
        Log.d(Sentry.TAG, "Sending up " + unsentRequests.size() + " cached response(s)");
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
        Sentry.captureException(t, SentryEventLevel.ERROR);
    }

    public static void captureException(Throwable t, SentryEventLevel level) {
        String culprit = getCause(t, t.getMessage());

        Sentry.captureEvent(new SentryEventBuilder()
                        .setMessage(t.getMessage())
                        .setCulprit(culprit)
                        .setLevel(level)
                        .setException(t)
        );

    }

    public static void captureUncaughtException(Context context, Throwable t) {
        final Writer result = new StringWriter();
        final PrintWriter printWriter = new PrintWriter(result);
        t.printStackTrace(printWriter);
        try {
            // Random number to avoid duplicate files
            long random = System.currentTimeMillis();

            // Embed version in stacktrace filename
            File stacktrace = new File(getStacktraceLocation(context), "raven-" + String.valueOf(random) + ".stacktrace");
            Log.d(TAG, "Writing unhandled exception to: " + stacktrace.getAbsolutePath());

            // Write the stacktrace to disk
            ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(stacktrace));
            oos.writeObject(t);
            oos.flush();
            // Close up everything
            oos.close();
        } catch (Exception ebos) {
            // Nothing much we can do about this - the game is over
            ebos.printStackTrace();
        }

        Log.d(TAG, result.toString());
    }

    private static String getCause(Throwable t, String culprit) {
        for (StackTraceElement stackTrace : t.getStackTrace()) {
            if (stackTrace.toString().contains(Sentry.getInstance().packageName)) {
                culprit = stackTrace.toString();
                break;
            }
        }

        return culprit;
    }

    private static File getStacktraceLocation(Context context) {
        return new File(context.getCacheDir(), "crashes");
    }

    private static String getStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    public static void captureEvent(SentryEventBuilder builder) {
        final SentryEventRequest request;
        if (Sentry.getInstance().captureListener != null) {

            builder = Sentry.getInstance().captureListener.beforeCapture(builder);
            if (builder == null) {
                Log.e(Sentry.TAG, "SentryEventBuilder in captureEvent is null");
                return;
            }

            request = new SentryEventRequest(builder);
        } else {
            request = new SentryEventRequest(builder);
        }

        Log.d(TAG, "Request - " + request.getRequestData());
        doCaptureEventPost(request);

    }

    private static boolean shouldAttemptPost() {
        PackageManager pm = Sentry.getInstance().context.getPackageManager();
        int hasPerm = pm.checkPermission(android.Manifest.permission.ACCESS_NETWORK_STATE, Sentry.getInstance().context.getPackageName());
        if (hasPerm == PackageManager.PERMISSION_DENIED) {
            return true;
        }

        ConnectivityManager connectivityManager = (ConnectivityManager) Sentry.getInstance().context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    public static HttpClient getHttpsClient(HttpClient client) {
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

        if (!shouldAttemptPost()) {
            InternalStorage.getInstance().addRequest(request);
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {

                HttpClient httpClient;
                if (Sentry.getInstance().verifySsl != 0) {
                    httpClient = new DefaultHttpClient();
                } else {
                    httpClient = getHttpsClient(new DefaultHttpClient());
                }
                HttpPost httpPost = new HttpPost(Sentry.getInstance().baseUrl + "/api/" + getProjectId() + "/store/");

                int TIMEOUT_MILLISEC = 10000;  // = 20 seconds
                HttpParams httpParams = httpPost.getParams();
                HttpConnectionParams.setConnectionTimeout(httpParams, TIMEOUT_MILLISEC);
                HttpConnectionParams.setSoTimeout(httpParams, TIMEOUT_MILLISEC);

                boolean success = false;
                try {
                    httpPost.setHeader("X-Sentry-Auth", createXSentryAuthHeader());
                    httpPost.setHeader("User-Agent", "sentry-android/" + VERSION);
                    httpPost.setHeader("Content-Type", "text/html; charset=utf-8");

                    httpPost.setEntity(new StringEntity(request.getRequestData()));
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

                    Log.d(TAG, "SendEvent - " + status + " " + stringResponse);
                } catch (ClientProtocolException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
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

        }).start();

    }

    private void setupUncaughtExceptionHandler() {

        UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
        if (currentHandler != null) {
            Log.d("Debugged", "current handler class=" + currentHandler.getClass().getName());
        }

        // don't register again if already registered
        if (!(currentHandler instanceof SentryUncaughtExceptionHandler)) {
            // Register default exceptions handler
            Thread.setDefaultUncaughtExceptionHandler(
                    new SentryUncaughtExceptionHandler(currentHandler, context));
        }

        sendAllCachedCapturedEvents();
    }

    private static class LazyHolder {
        private static Sentry instance = new Sentry();
    }

    public static class ExSSLSocketFactory extends SSLSocketFactory {
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

    private static class InternalStorage {

        private final static String FILE_NAME = "unsent_requests";
        private ArrayList<SentryEventRequest> unsentRequests;

        private InternalStorage() {
            this.unsentRequests = this.readObject(Sentry.getInstance().context);
        }

        private static InternalStorage getInstance() {
            return LazyHolder.instance;
        }

        /**
         * @return the unsentRequests
         */
        public ArrayList<SentryEventRequest> getUnsentRequests() {
            return unsentRequests;
        }

        public void addRequest(SentryEventRequest request) {
            synchronized (this) {
                Log.d(Sentry.TAG, "Adding request - " + request.uuid);
                if (!this.unsentRequests.contains(request)) {
                    this.unsentRequests.add(request);
                    this.writeObject(Sentry.getInstance().context, this.unsentRequests);
                }
            }
        }

        public void removeBuilder(SentryEventRequest request) {
            synchronized (this) {
                Log.d(Sentry.TAG, "Removing request - " + request.uuid);
                this.unsentRequests.remove(request);
                this.writeObject(Sentry.getInstance().context, this.unsentRequests);
            }
        }

        private void writeObject(Context context, ArrayList<SentryEventRequest> requests) {
            try {
                FileOutputStream fos = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE);
                ObjectOutputStream oos = new ObjectOutputStream(fos);
                oos.writeObject(requests);
                oos.close();
                fos.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private ArrayList<SentryEventRequest> readObject(Context context) {
            try {
                FileInputStream fis = context.openFileInput(FILE_NAME);
                ObjectInputStream ois = new ObjectInputStream(fis);
                ArrayList<SentryEventRequest> requests = (ArrayList<SentryEventRequest>) ois.readObject();
                return requests;
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (StreamCorruptedException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
            return new ArrayList<SentryEventRequest>();
        }

        private static class LazyHolder {
            private static InternalStorage instance = new InternalStorage();
        }
    }

    public abstract static class SentryEventCaptureListener {

        public abstract SentryEventBuilder beforeCapture(SentryEventBuilder builder);

    }

    public static class SentryEventRequest implements Serializable {
        private String requestData;
        private UUID uuid;

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

    public static class SentryEventBuilder implements Serializable {

        private static final long serialVersionUID = -8589756678369463988L;

        private final static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

        static {
            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        }

        private Map<String, Object> event;

        public SentryEventBuilder() {
            event = new HashMap<String, Object>();
            event.put("event_id", UUID.randomUUID().toString().replace("-", ""));
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
                if (className.startsWith("android.")
                        || className.startsWith("java.")
                        || className.startsWith("dalvik.")
                        || className.startsWith("com.android.")) {

                    inApp = false;
                }

                frame.put("in_app", inApp);

                frameList.put(frame);
            }

            JSONObject frameHash = new JSONObject();
            frameHash.put("frames", frameList);

            return frameHash;
        }

        /**
         * "message": "SyntaxError: Wattttt!"
         *
         * @param message
         * @return
         */
        public SentryEventBuilder setMessage(String message) {
            event.put("message", message);
            return this;
        }

        /**
         * "timestamp": "2011-05-02T17:41:36"
         *
         * @param timestamp
         * @return
         */
        public SentryEventBuilder setTimestamp(long timestamp) {
            event.put("timestamp", sdf.format(new Date(timestamp)));
            return this;
        }

        /**
         * "level": "warning"
         *
         * @param level
         * @return
         */
        public SentryEventBuilder setLevel(SentryEventLevel level) {
            event.put("level", level.value);
            return this;
        }

        /**
         * "logger": "my.logger.name"
         *
         * @param logger
         * @return
         */
        public SentryEventBuilder setLogger(String logger) {
            event.put("logger", logger);
            return this;
        }

        /**
         * "culprit": "my.module.function_name"
         *
         * @param culprit
         * @return
         */
        public SentryEventBuilder setCulprit(String culprit) {
            event.put("culprit", culprit);
            return this;
        }

        /**
         * @param tags
         * @return
         */
        public SentryEventBuilder setTags(Map<String, String> tags) {
            setTags(new JSONObject(tags));
            return this;
        }

        public JSONObject getTags() {
            if (!event.containsKey("tags")) {
                setTags(new HashMap<String, String>());
            }

            return (JSONObject) event.get("tags");
        }

        public SentryEventBuilder setTags(JSONObject tags) {
            event.put("tags", tags);
            return this;
        }

        /**
         * @param serverName
         * @return
         */
        public SentryEventBuilder setServerName(String serverName) {
            event.put("server_name", serverName);
            return this;
        }

        /**
         * @param name
         * @param version
         * @return
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
         * @param extra
         * @return
         */
        public SentryEventBuilder setExtra(Map<String, String> extra) {
            setExtra(new JSONObject(extra));
            return this;
        }

        public JSONObject getExtra() {
            if (!event.containsKey("extra")) {
                setExtra(new HashMap<String, String>());
            }

            return (JSONObject) event.get("extra");
        }

        public SentryEventBuilder setExtra(JSONObject extra) {
            event.put("extra", extra);
            return this;
        }

        /**
         * @param t
         * @return
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

        public static enum SentryEventLevel {

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
    }

    private class SentryUncaughtExceptionHandler implements UncaughtExceptionHandler {

        private UncaughtExceptionHandler defaultExceptionHandler;
        private Context context;

        // constructor
        public SentryUncaughtExceptionHandler(UncaughtExceptionHandler pDefaultExceptionHandler, Context context) {
            defaultExceptionHandler = pDefaultExceptionHandler;
            this.context = context;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable e) {
            // Here you should have a more robust, permanent record of problems
            SentryEventBuilder builder = new SentryEventBuilder(e, SentryEventBuilder.SentryEventLevel.FATAL);
            if (Sentry.getInstance().captureListener != null) {
                builder = Sentry.getInstance().captureListener.beforeCapture(builder);
            }

            if (builder != null) {
                InternalStorage.getInstance().addRequest(new SentryEventRequest(builder));
            } else {
                Log.e(Sentry.TAG, "SentryEventBuilder in uncaughtException is null");
            }

            //call original handler
            defaultExceptionHandler.uncaughtException(thread, e);
        }

    }

}
