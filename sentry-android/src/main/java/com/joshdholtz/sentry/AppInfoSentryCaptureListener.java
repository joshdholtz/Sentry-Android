package com.joshdholtz.sentry;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import org.json.JSONException;
import org.json.JSONObject;

public class AppInfoSentryCaptureListener implements Sentry.SentryEventCaptureListener {


    private final JSONObject contexts;
    private Sentry.SentryEventCaptureListener otherListener;

    public AppInfoSentryCaptureListener(Context context,Sentry.SentryEventCaptureListener otherListener) {
        this.otherListener = otherListener;
        AppInfo appInfo = AppInfo.read(context);
        contexts = readContexts(context, appInfo);
    }

    public AppInfoSentryCaptureListener(Context context) {
        this(context,null);
    }

    @Override
    public Sentry.SentryEventBuilder beforeCapture(Sentry.SentryEventBuilder builder) {
        Sentry.SentryEventBuilder eventBuilder = builder.setContexts(contexts);
        if (otherListener == null) {
            return eventBuilder;
        }
        return otherListener.beforeCapture(eventBuilder);
    }

    private static JSONObject readContexts(Context context, AppInfo appInfo) {
        final JSONObject contexts = new JSONObject();
        try {
            contexts.put("os", osContext());
            contexts.put("device", deviceContext(context));
            contexts.put("package", packageContext(appInfo));
        } catch (JSONException e) {
            Log.e(Sentry.TAG, "Failed to build device contexts", e);
        }
        return contexts;
    }

    /**
     * Read the package data into map to be sent as an event context item.
     * This is not a built-in context type.
     */
    private static JSONObject packageContext(AppInfo appInfo) {
        final JSONObject pack = new JSONObject();
        try {
            pack.put("type", "package");
            pack.put("name", appInfo.name);
            pack.put("version_name", appInfo.versionName);
            pack.put("version_code", Integer.toString(appInfo.versionCode));
        } catch (JSONException e) {
            Log.e(Sentry.TAG, "Error reading package context", e);
        }
        return pack;
    }


    /**
     * Read the device and build into a map.
     * <p>
     * Not implemented:
     * -  battery_level
     * If the device has a battery this can be an integer defining the battery level (in
     * the range 0-100). (Android requires registration of an intent to query the battery).
     * - name
     * The name of the device. This is typically a hostname.
     * <p>
     * See https://docs.getsentry.com/hosted/clientdev/interfaces/#context-types
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
            if (!TextUtils.isEmpty(architecture)) {
                device.put("arch", architecture);
            }

            final int orient = context.getResources().getConfiguration().orientation;
            device.put("orientation", orient == Configuration.ORIENTATION_LANDSCAPE ?
                "landscape" : "portrait");

            // Read screen resolution in the format "800x600"
            // Normalised to have wider side first.
            final Object windowManager = context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager instanceof WindowManager) {
                final DisplayMetrics metrics = new DisplayMetrics();
                ((WindowManager) windowManager).getDefaultDisplay().getMetrics(metrics);
                device.put("screen_resolution",
                    String.format("%sx%s",
                        Math.max(metrics.widthPixels, metrics.heightPixels),
                        Math.min(metrics.widthPixels, metrics.heightPixels)));
            }

        } catch (Exception e) {
            Log.e(Sentry.TAG, "Error reading device context", e);
        }
        return device;
    }


    private static JSONObject osContext() {
        final JSONObject os = new JSONObject();
        try {
            os.put("type", "os");
            os.put("name", "Android");
            os.put("version", Build.VERSION.RELEASE);
            os.put("build", Integer.toString(Build.VERSION.SDK_INT));

            final String kernelVersion = System.getProperty("os.version");
            if (!TextUtils.isEmpty(kernelVersion)) {
                os.put("kernel_version", kernelVersion);
            }

        } catch (Exception e) {
            Log.e(Sentry.TAG, "Error reading OS context", e);
        }
        return os;
    }

    /**
         * Store a tuple of package version information captured from PackageInfo
         *
         * @see PackageInfo
         */
        private final static class AppInfo {
            static final AppInfo Empty = new AppInfo("", "", 0);
            final String name;
            final String versionName;
            final int versionCode;

            AppInfo(String name, String versionName, int versionCode) {
                this.name = name;
                this.versionName = versionName;
                this.versionCode = versionCode;
            }

            static AppInfo read(final Context context) {
                try {
                    final PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                    return new AppInfo(info.packageName, info.versionName, info.versionCode);
                } catch (Exception e) {
                    Log.e(Sentry.TAG, "Error reading package context", e);
                    return Empty;
                }
            }
        }
}
