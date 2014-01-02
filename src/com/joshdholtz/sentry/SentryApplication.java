package com.joshdholtz.sentry;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONException;

import com.joshdholtz.sentry.Sentry.SentryEventBuilder;
import com.joshdholtz.sentry.Sentry.SentryEventCaptureListener;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

public class SentryApplication extends Application {

	@Override
	public void onCreate() {
		super.onCreate();
		
		// Sets a listener to intercept the SentryEventBuilder before 
		// each capture to set values that could change state
		Sentry.setCaptureListener(new SentryEventCaptureListener() {

			@Override
			public SentryEventBuilder beforeCapture(SentryEventBuilder builder) {

				// Needs permission - <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
				ConnectivityManager connManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
				NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

				// Sets extra key if wifi is connected
				try {
					builder.getExtra().put("wifi", String.valueOf(mWifi.isConnected()));
					builder.getTags().put("tag_1", "hello");
				} catch (JSONException e) {}

				return builder;
			}

		});
		
		// Sentry will look for uncaught exceptions from previous runs and send them        
		Sentry.init(this, "https://app.getsentry.com", "YOUR-DSN");

		
//		Map<String, String> tags = new HashMap<String, String>();
//		tags.put("tag1", "value1");
//		tags.put("tag2", "value2");
//		
//		// Capture event
//		Sentry.captureEvent(new Sentry.SentryEventBuilder()
//			.setMessage("Being so awesome at stuff 3")
//			.setCulprit("Josh D Holtz")
//			.setTags(tags)
//			.setTimestamp(System.currentTimeMillis())
//		);
		
	}
	
}
