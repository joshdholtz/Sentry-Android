package com.joshdholtz.sentry;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONException;

import com.joshdholtz.sentry.Sentry.SentryEventBuilder;
import com.joshdholtz.sentry.Sentry.SentryEventBuilder.SentryEventLevel;
import com.joshdholtz.sentry.Sentry.SentryEventCaptureListener;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.app.Activity;
import android.view.Menu;

public class MainActivity extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}

}
