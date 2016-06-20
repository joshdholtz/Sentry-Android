package com.joshdholtz.sentryapp;

import android.app.Application;
import com.joshdholtz.sentry.SentryInstance;
import com.joshdholtz.sentry.http.apache.ApacheHttpRequestSender;

public class SentryApplication extends Application
{

	@Override
	public void onCreate()
	{
		super.onCreate();
		SentryInstance.init(this, "your-dsn",new ApacheHttpRequestSender());
		SentryInstance.getInstance().setDebugLogging(BuildConfig.DEBUG);
	}
}
