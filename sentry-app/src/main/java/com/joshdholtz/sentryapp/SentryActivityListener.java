package com.joshdholtz.sentryapp;
import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Bundle;

import com.joshdholtz.sentry.Sentry;


public class SentryActivityListener implements Application.ActivityLifecycleCallbacks {

    private String lastActivity = "";

    private static String activityName(final Activity activity) {
        PackageManager packageManager = activity.getPackageManager();
        try {
            return packageManager.getActivityInfo(activity.getComponentName(), 0).name;
        } catch (Exception e) {
            return "";
        }
    }

    private void navigatedTo(final Activity activity) {
        final String name = activityName(activity);

        if (name.equals(lastActivity)) {
            return;
        }

        Sentry.addNavigationBreadcrumb("activity", lastActivity, name);
        lastActivity = name;
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
        navigatedTo(activity);
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
