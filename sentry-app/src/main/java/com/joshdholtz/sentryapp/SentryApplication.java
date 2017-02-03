package com.joshdholtz.sentryapp;

import android.app.Application;

import com.joshdholtz.sentry.AppInfoSentryCaptureListener;
import com.joshdholtz.sentry.SentryInstance;
import com.joshdholtz.sentry.http.apache.ApacheHttpRequestSender;

public class SentryApplication extends Application
{

	@Override
	public void onCreate()
	{
		super.onCreate();
		SentryInstance.init(this, "your-dsn-without-credentials",new ApacheHttpRequestSender(), "login","password");
		SentryInstance.getInstance().setDebugLogging(BuildConfig.DEBUG);
        SentryInstance.getInstance().setCaptureListener(new AppInfoSentryCaptureListener(this));
	}
}
