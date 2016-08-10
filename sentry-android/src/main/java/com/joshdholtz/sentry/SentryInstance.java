package com.joshdholtz.sentry;

import android.content.Context;
import android.util.Pair;

public final class SentryInstance {

    private static final String FILE_NAME = "unsent_requests";
    private static SentryInstance ourInstance = new SentryInstance();
    private Sentry sentry;

    public static void init(Context context, String dsnWithoutCredentials, HttpRequestSender httpRequestSender, Pair<String, String> credentials) {
        ourInstance.sentry = Sentry.newInstance(context, dsnWithoutCredentials, httpRequestSender, FILE_NAME, credentials);
    }

    /**
     * {@link #init(Context, String, HttpRequestSender, Pair)} must be called before this
     *
     * @return {@code Sentry} instance created in {@link #init(Context, String, HttpRequestSender, Pair)} (Context, String, HttpRequestSender)}
     */
    public static Sentry getInstance() {
        return ourInstance.sentry;
    }

    private SentryInstance() {
    }
}
