package com.joshdholtz.sentry;

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
				} catch (JSONException e) {}

				return builder;
			}

		});
		
		// Sentry will look for uncaught exceptions from previous runs and send them        
		Sentry.init(this, "YOUR-DSN");

		
		// Capture event
		Sentry.captureEvent(new Sentry.SentryEventBuilder()
			.setMessage("Being so awesome at stuff 2")
			.setCulprit("Josh D Holtz")
			.setTimestamp(System.currentTimeMillis())
		);
		
	}
	
}
