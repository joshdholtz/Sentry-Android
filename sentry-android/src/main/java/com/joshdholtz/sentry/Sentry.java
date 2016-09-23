package com.joshdholtz.sentry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.Writer;
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
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.joshdholtz.sentry.Sentry.SentryEventBuilder.SentryEventLevel;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

public class Sentry {

	private final static String VERSION = "0.2.0";

	private Context context;

	public final static String sentryVersion = "7";
	public static boolean debug = false;

	private String baseUrl;
	private String dsn;
	private String packageName;
	private int verifySsl;
	private SentryEventCaptureListener captureListener;
	private JSONObject contexts = new JSONObject();

	private static final String TAG = "Sentry";

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
		Sentry.getInstance().context = context.getApplicationContext();

		Uri uri = Uri.parse(dsn);
		String port = "";
		if (uri.getPort() >= 0) {
			port = ":" + uri.getPort();
		}

		Sentry.getInstance().baseUrl = uri.getScheme() + "://" + uri.getHost() + port;
		Sentry.getInstance().dsn = dsn;
		Sentry.getInstance().packageName = context.getPackageName();
		Sentry.getInstance().verifySsl = getVerifySsl(dsn);
		Sentry.getInstance().contexts = readContexts(Sentry.getInstance().context);

		if (setupUncaughtExceptionHandler) {
			Sentry.getInstance().setupUncaughtExceptionHandler();
		}
	}
	
	private static int getVerifySsl(String dsn) {
		int verifySsl = 1;
		List<NameValuePair> params = getAllGetParams(dsn);
		for (NameValuePair param : params) {
			if(param.getName().equals("verify_ssl"))
				return Integer.parseInt(param.getValue());
		}
		return verifySsl;
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
					new SentryUncaughtExceptionHandler(currentHandler));
		}
		
		sendAllCachedCapturedEvents();
	}

	private static String createXSentryAuthHeader() {
		String header = "";

		Uri uri = Uri.parse(Sentry.getInstance().dsn);

		String authority = uri.getAuthority().replace("@" + uri.getHost(), "");

		String[] authorityParts = authority.split(":");
		String publicKey = authorityParts[0];
		String secretKey = authorityParts[1];

		header += "Sentry sentry_version=" + sentryVersion + ",";
		header += "sentry_client=sentry-android/" + VERSION + ",";
		header += "sentry_timestamp=" + System.currentTimeMillis() +",";
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

	public static void captureException(Throwable t, String message, SentryEventLevel level) {
		String culprit = getCause(t, t.getMessage());

		Sentry.captureEvent(new SentryEventBuilder()
		.setMessage(message)
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
			File stacktrace = new File(getStacktraceLocation(context), "raven-" +  String.valueOf(random) + ".stacktrace");
			log("Writing unhandled exception to: " + stacktrace.getAbsolutePath());

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

		log(result.toString());
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
		builder.event.put("contexts", Sentry.getInstance().contexts);
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

		log("Request - " + request.getRequestData());

		// Check if on main thread - if not, run on main thread
		if (Looper.myLooper() == Looper.getMainLooper()) {
			doCaptureEventPost(request);
		} else if (Sentry.getInstance().context != null) {

			HandlerThread thread = new HandlerThread("SentryThread") {};
			thread.start();
			Runnable runnable = new Runnable() {
				@Override
				public void run() {
					doCaptureEventPost(request);
				}
			};
			Handler h = new Handler(thread.getLooper());
			h.post(runnable);

		}

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

		        sslContext.init(null, new TrustManager[] { x509TrustManager }, null);
		    }

		    public ExSSLSocketFactory(SSLContext context) throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException {
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
		
		new AsyncTask<Void, Void, Void>(){
			@Override
			protected Void doInBackground(Void... params) {

				int projectId = Integer.parseInt(getProjectId());
				String url = Sentry.getInstance().baseUrl + "/api/" + projectId + "/store/";

				log("Sending to URL - " + url);

				HttpClient httpClient;
				if(Sentry.getInstance().verifySsl != 0) {
					log("Using http client");
					httpClient = new DefaultHttpClient();
				}
				else {
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
					httpPost.setHeader("X-Sentry-Auth", createXSentryAuthHeader());
					httpPost.setHeader("User-Agent", "sentry-android/" + VERSION);
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

				return null;
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

		}.execute();

	}

	private class SentryUncaughtExceptionHandler implements UncaughtExceptionHandler {

		private final UncaughtExceptionHandler defaultExceptionHandler;

		// constructor
		public SentryUncaughtExceptionHandler(UncaughtExceptionHandler pDefaultExceptionHandler) {
			defaultExceptionHandler = pDefaultExceptionHandler;
		}

		@Override
		public void uncaughtException(Thread thread, Throwable e) {
			// Here you should have a more robust, permanent record of problems
			SentryEventBuilder builder = new SentryEventBuilder(e, SentryEventLevel.FATAL);
			if (Sentry.getInstance().captureListener != null) {
				builder = Sentry.getInstance().captureListener.beforeCapture(builder);
			}

            if (builder != null) {
                builder.event.put("contexts", Sentry.getInstance().contexts);
                InternalStorage.getInstance().addRequest(new SentryEventRequest(builder));
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
				e.printStackTrace();
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
			synchronized(this) {
				log("Adding request - " + request.uuid);
				if (!this.unsentRequests.contains(request)) {
					this.unsentRequests.add(request);
					this.writeObject(Sentry.getInstance().context, this.unsentRequests);
				}
			}
		}
		
		public void removeBuilder(SentryEventRequest request) {
			synchronized(this) {
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
				e.printStackTrace();
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
				e.printStackTrace();
			}
			return new ArrayList<>();
		}
	}

	public interface SentryEventCaptureListener {

		SentryEventBuilder beforeCapture(SentryEventBuilder builder);

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

	public static class SentryEventBuilder implements Serializable {

		private static final long serialVersionUID = -8589756678369463988L;

		// Match packages names that start with some well-known internal class-paths:
		// java.*
		// android.*
		// com.android.*
		// com.google.android.*
		// dalvik.system.*
		static final String isInternalPackage = "^(java|android|com\\.android|com\\.google\\.android|dalvik\\.system)\\..*";
		
		private final static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
		static {
			sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		}

		private final Map<String, Object> event;

		public JSONObject toJSON() {
			return new JSONObject(event);
		}

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
		 * @param message Message
		 * @return SentryEventBuilder
		 */
		public SentryEventBuilder setMessage(String message) {
			event.put("message", message);
			return this;
		}

		/**
		 * "timestamp": "2011-05-02T17:41:36"
		 * @param timestamp Timestamp
		 * @return SentryEventBuilder
		 */
		public SentryEventBuilder setTimestamp(long timestamp) {
			event.put("timestamp", sdf.format(new Date(timestamp)));
			return this;
		}

		/**
		 * "level": "warning"
		 * @param level Level
		 * @return SentryEventBuilder
		 */
		public SentryEventBuilder setLevel(SentryEventLevel level) {
			event.put("level", level.value);
			return this;
		}

		/**
		 * "logger": "my.logger.name"
		 * @param logger Logger
		 * @return SentryEventBuilder
		 */
		public SentryEventBuilder setLogger(String logger) {
			event.put("logger", logger);
			return this;
		}

		/**
		 * "culprit": "my.module.function_name"
		 * @param culprit Culprit
		 * @return SentryEventBuilder
		 */
		public SentryEventBuilder setCulprit(String culprit) {
			event.put("culprit", culprit);
			return this;
		}
		
		/**
		 * 
		 * @param user User
		 * @return SentryEventBuilder
		 */
		public SentryEventBuilder setUser(Map<String,String> user) {
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
		 * 
		 * @param tags Tags
		 * @return SentryEventBuilder
		 */
		public SentryEventBuilder setTags(Map<String,String> tags) {
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
		 * 
		 * @param serverName Server name
		 * @return SentryEventBuilder
		 */
		public SentryEventBuilder setServerName(String serverName) {
			event.put("server_name", serverName);
			return this;
		}
		
		/**
		 * 
		 * @param release Release
		 * @return SentryEventBuilder
		 */
		public SentryEventBuilder setRelease(String release) {
			event.put("release", release);
			return this;
		}

		/**
		 * 
		 * @param name Name
		 * @param version Version
		 * @return SentryEventBuilder
		 */
		public SentryEventBuilder addModule(String name, String version) {
			JSONArray modules;
			if (!event.containsKey("modules")) {
				modules = new JSONArray();
				event.put("modules", modules);
			} else {
				modules = (JSONArray)event.get("modules");
			}

			if (name != null && version != null) {
				String[] module = {name, version};
				modules.put(new JSONArray(Arrays.asList(module)));
			}

			return this;
		}

		/**
		 * 
		 * @param extra Extra
		 * @return SentryEventBuilder
		 */
		public SentryEventBuilder setExtra(Map<String,String> extra) {
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
		 *
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

        public static JSONObject getStackTrace(Throwable t) throws JSONException {
            JSONArray frameList = new JSONArray();

            for (StackTraceElement ste : t.getStackTrace()) {
                frameList.put(frameJson(ste));
            }

            JSONObject frameHash = new JSONObject();
            frameHash.put("frames", frameList);

            return frameHash;
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

	private static JSONObject readContexts(Context context) {
		final JSONObject contexts = new JSONObject();
		try {
			contexts.put("os", osContext());
			contexts.put("device", deviceContext(context));
			contexts.put("package", packageContext(context));
		} catch (JSONException e) {
			Log.e(TAG, "Failed to build device contexts", e);
		}
		return contexts;
	}

	/**
	 * Read the device and build into a map.
	 *
	 * Not implemented:
	 *   -  battery_level
	 *   If the device has a battery this can be an integer defining the battery level (in
	 *   the range 0-100). (Android requires registration of an intent to query the battery).
	 *   - name
	 *   The name of the device. This is typically a hostname.
	 *
	 *   @see https://docs.getsentry.com/hosted/clientdev/interfaces/#context-types
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
	private static JSONObject packageContext(Context context) {
		final JSONObject pack = new JSONObject();
		try {
			final String packageName = context.getPackageName();
			final PackageInfo packageInfo = context.getPackageManager().getPackageInfo(packageName, 0);
			pack.put("type", "package");
			pack.put("name", packageName);
			pack.put("version_name", packageInfo.versionName);
			pack.put("version_code", Integer.toString(packageInfo.versionCode));
		} catch (Exception e) {
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
