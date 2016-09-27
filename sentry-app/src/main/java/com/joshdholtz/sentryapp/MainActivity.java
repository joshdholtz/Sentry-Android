package com.joshdholtz.sentryapp;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.joshdholtz.sentry.Sentry;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String yourDSN = "https://cd95d48687a54ee1840a16ecef394c93:c9def31f1d5940b18b2a9b4ba149b19d@sentry.io/75499";
        Sentry.init(this, yourDSN);
        Sentry.debug = true;

        Sentry.addNavigationBreadcrumb("activity.main", "here", "there");
        Sentry.addHttpBreadcrumb("http://example.com", "GET", 202);

        Sentry.captureEvent(new Sentry.SentryEventBuilder()
            .setMessage("OMG this works woooo")
            .setStackTrace(Thread.currentThread().getStackTrace())
        );

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    private void crash() {
        String s = null;
        s.length();
    }

    public void onClickBreak(View view) {
        Sentry.addBreadcrumb("button.click", "break button");
        crash();

    }

    public void onClickCapture(View view) {
        Sentry.addBreadcrumb("button.click", "capture button");
        try {
            Sentry.captureEvent(new Sentry.SentryEventBuilder()
                .setMessage("test from view")
                .setThreads(Thread.getAllStackTraces()));
        } catch (Exception e) {
            Sentry.captureException(e, "Exception caught in click handler");
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
