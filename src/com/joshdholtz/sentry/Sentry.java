package com.joshdholtz.sentry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import org.apache.http.HttpResponse;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.joshdholtz.protocol.lib.ProtocolClient;
import com.joshdholtz.protocol.lib.requests.JSONRequestData;
import com.joshdholtz.protocol.lib.responses.ProtocolResponseHandler;
import com.joshdholtz.sentry.Sentry.SentryEventBuilder.SentryEventLevel;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

public class Sentry {
	
	private final static String VERSION = "0.1.2";

	private String dsn;
	private String packageName;
	
	private ProtocolClient client;

	private static final String TAG = "Sentry";
	private static final String BASE_URL = "https://app.getsentry.com";
	
	private Sentry() {

	}

	private static Sentry getInstance() {
		return LazyHolder.instance;
	}


	private static class LazyHolder {
		private static Sentry instance = new Sentry();
	}

	public static void init(Context context, String dsn) {
		Sentry.getInstance().dsn = dsn;
		Sentry.getInstance().packageName = context.getPackageName();
		
		Sentry.getInstance().client = new ProtocolClient(BASE_URL);
		Sentry.getInstance().client.setDebug(true);
		
		submitStackTraces(context);

		UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
		if (currentHandler != null) {
			Log.d("Debugged", "current handler class="+currentHandler.getClass().getName());
		}       
		// don't register again if already registered
		if (!(currentHandler instanceof SentryUncaughtExceptionHandler)) {
			// Register default exceptions handler
			Thread.setDefaultUncaughtExceptionHandler(
					new SentryUncaughtExceptionHandler(currentHandler, context));
		}
	}
	
	private static String createXSentryAuthHeader() {
		String header = "";
		
		Uri uri = Uri.parse(Sentry.getInstance().dsn);
		String authority = uri.getAuthority().replace("@" + uri.getHost(), "");
		
		String[] authorityParts = authority.split(":");
		String publicKey = authorityParts[0];
		String secretKey = authorityParts[1];
		
		header += "Sentry sentry_version=4,";
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
			final String filePath = context.getFilesDir().getAbsolutePath();

			// Random number to avoid duplicate files
			long random = System.currentTimeMillis();

			// Embed version in stacktrace filename
			String filename = "Raven-" +  String.valueOf(random);
			Log.d(TAG, "Writing unhandled exception to: " + filePath + "/" + filename + ".stacktrace");

			// Write the stacktrace to disk
			ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filePath + "/" + filename + ".stacktrace"));
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
	
	private static String getStackTrace(Throwable t) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		t.printStackTrace(pw);
		return sw.toString();
	}
	
	public static void captureEvent(SentryEventBuilder builder) {
		JSONRequestData requestData = new JSONRequestData(builder.event);
		requestData.addHeader("X-Sentry-Auth", createXSentryAuthHeader());
		requestData.addHeader("User-Agent", "sentry-android/" + VERSION);
		
		Log.d(TAG, "Request - " + new JSONObject(builder.event).toString());
		
		Sentry.getInstance().client.doPost("/api/" + getProjectId() + "/store/", requestData, new ProtocolResponseHandler() {

		    @Override
		    public void handleResponse(HttpResponse response, int status, byte[] data) {
		        String responseData = new String(data);
		        Log.d(TAG, "SendEvent - " + status + " " + responseData);
		    }

		});
	}

	private static class SentryUncaughtExceptionHandler implements UncaughtExceptionHandler {

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
			Sentry.captureUncaughtException(context, e);

			//call original handler  
			defaultExceptionHandler.uncaughtException(thread, e);  
		}

	}

	private static String[] searchForStackTraces(Context context) {
		File dir = new File(context.getFilesDir().getAbsolutePath() + "/");
		// Try to create the files folder if it doesn't exist
		dir.mkdir();
		// Filter for ".stacktrace" files
		FilenameFilter filter = new FilenameFilter() { 
			public boolean accept(File dir, String name) {
				return name.endsWith(".stacktrace"); 
			} 
		}; 
		return dir.list(filter); 
	}

	private static void submitStackTraces(final Context context) {
		try {
			Log.d(TAG, "Looking for exceptions in thing");
			String[] list = searchForStackTraces(context);
			if (list != null && list.length > 0) {
				Log.d(TAG, "Found "+list.length+" stacktrace(s)");
				for (int i=0; i < list.length; i++) {
					String filePath = context.getFilesDir().getAbsolutePath()+"/"+list[i];

					ObjectInputStream ois = new ObjectInputStream(new FileInputStream(filePath));
					Throwable t = (Throwable) ois.readObject();
					ois.close();

					captureException(t, SentryEventLevel.FATAL);
					
					Log.d(TAG, t.getMessage());
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				String[] list = searchForStackTraces(context);
				for ( int i = 0; i < list.length; i ++ ) {
					File file = new File(context.getFilesDir().getAbsolutePath()+"/"+list[i]);
					file.delete();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

	}
	
	public static class SentryEventBuilder {
		
		private final static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
		static {
			sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		}
		
		private Map<String, Object> event;
		
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
		
		public SentryEventBuilder() {
			event = new HashMap<String, Object>();
			event.put("event_id", UUID.randomUUID().toString().replace("-", ""));
			this.setPlatform("android");
			this.setTimestamp(System.currentTimeMillis());
		}
		
		/**
		 * "message": "SyntaxError: Wattttt!"
		 * @param message
		 * @return
		 */
		public SentryEventBuilder setMessage(String message) {
			event.put("message", message);
			return this;
		}
		
		/**
		 * "timestamp": "2011-05-02T17:41:36"
		 * @param timestamp
		 * @return
		 */
		public SentryEventBuilder setTimestamp(long timestamp) {
			event.put("timestamp", sdf.format(new Date(timestamp)));
			return this;
		}
		
		/**
		 * "level": "warning"
		 * @param level
		 * @return
		 */
		public SentryEventBuilder setLevel(SentryEventLevel level) {
			event.put("level", level.value);
			return this;
		}
		
		/**
		 * "logger": "my.logger.name"
		 * @param logger
		 * @return
		 */
		public SentryEventBuilder setLogger(String logger) {
			event.put("logger", logger);
			return this;
		}
		
		/**
		 * "platform": "python"
		 * @param platform
		 * @return
		 */
		public SentryEventBuilder setPlatform(String platform) {
			event.put("platform", platform);
			return this;
		}
		
		/**
		 * "culprit": "my.module.function_name"
		 * @param culprit
		 * @return
		 */
		public SentryEventBuilder setCulprit(String culprit) {
			event.put("culprit", culprit);
			return this;
		}
		
		/**
		 * 
		 * @param tags
		 * @return
		 */
		public SentryEventBuilder setTags(Map<String,String> tags) {
			event.put("tags", tags);
			return this;
		}
		
		/**
		 * 
		 * @param serverName
		 * @return
		 */
		public SentryEventBuilder setServerName(String serverName) {
			event.put("server_name", serverName);
			return this;
		}
		
		/**
		 * 
		 * @param modules
		 * @return
		 */
		public SentryEventBuilder setModules(List<String> modules) {
			event.put("modules", modules);
			return this;
		}
		
		/**
		 * 
		 * @param extra
		 * @return
		 */
		public SentryEventBuilder setExtra(Map<String,String> extra) {
			event.put("extra", extra);
			return this;
		}
		
		/**
		 * 
		 * @param extra
		 * @return
		 */
		public SentryEventBuilder setException(Throwable t) {
			Map<String, Object> exception = new HashMap<String, Object>();
			exception.put("type", t.getClass().getSimpleName());
			exception.put("value", t.getMessage());
	        exception.put("module", t.getClass().getPackage().getName());
			
			event.put("sentry.interfaces.Exception", new JSONObject(exception));
			try {
				event.put("sentry.interfaces.Stacktrace", getStackTrace(t));
			} catch (JSONException e) { e.printStackTrace(); }
			
			return this;
		}
		
		public static JSONObject getStackTrace(Throwable t) throws JSONException {
			JSONArray array = new JSONArray();
			
	        while (t != null) {
	            StackTraceElement[] elements = t.getStackTrace();
	            for (int index = 0; index < elements.length; ++index) {
	                if (index == 0) {
	                	JSONObject causedByFrame = new JSONObject();
	                    String msg = "Caused by: " + t.getClass().getName();
	                    if (t.getMessage() != null) {
	                        msg += " (\"" + t.getMessage() + "\")";
	                    }
	                    causedByFrame.put("filename", msg);
	                    causedByFrame.put("lineno", -1);
	                    array.put(causedByFrame);
	                }
	                StackTraceElement element = elements[index];
	                JSONObject frame = new JSONObject();
	                frame.put("filename", element.getClassName());
	                frame.put("function", element.getMethodName());
	                frame.put("lineno", element.getLineNumber());
	                array.put(frame);
	            }
	            t = t.getCause();
	        }
	        JSONObject stackTrace = new JSONObject();
	        stackTrace.put("frames", array);
	        return stackTrace;
	    }
		
	}

}
