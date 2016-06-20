package com.joshdholtz.sentry;

import android.content.Context;

public final class SentryInstance {

    private static final String FILE_NAME = "unsent_requests";
    private static SentryInstance ourInstance = new SentryInstance();
    private Sentry sentry;

    public static void init(Context context, String dsn, HttpRequestSender httpRequestSender) {
        ourInstance.sentry = Sentry.newInstance(context, dsn, httpRequestSender, FILE_NAME);
    }

    /**
     * {@link #init(Context, String, HttpRequestSender)} must be called before this
     *
     * @return {@code Sentry} instance created in {@link #init(Context, String, HttpRequestSender)}
     */
    public static Sentry getInstance() {
        return ourInstance.sentry;
    }

    private SentryInstance() {
    }
}
