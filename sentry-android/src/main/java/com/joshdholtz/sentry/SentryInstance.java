package com.joshdholtz.sentry;

import android.content.Context;

public final class SentryInstance {

    private static final String FILE_NAME = "unsent_requests";
    private static SentryInstance ourInstance = new SentryInstance();
    private Sentry sentry;

    public static void init(Context context, String dsnWithoutCredentials, HttpRequestSender httpRequestSender, String publicKey,String secretKey) {
        ourInstance.sentry = Sentry.newInstance(context, dsnWithoutCredentials, httpRequestSender, FILE_NAME, publicKey, secretKey);
    }

    /**
     * {@link #init(Context, String, HttpRequestSender, String, String)} must be called before this
     *
     * @return {@code Sentry} instance created in {@link #init(Context, String, HttpRequestSender, String, String)} (Context, String, HttpRequestSender, Pair)} (Context, String, HttpRequestSender)}
     */
    public static Sentry getInstance() {
        return ourInstance.sentry;
    }

    private SentryInstance() {
    }
}
