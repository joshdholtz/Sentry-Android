package com.joshdholtz.sentryapp;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.joshdholtz.sentry.Sentry;
import com.joshdholtz.sentry.SentryInstance;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Sentry sentry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sentry = SentryInstance.getInstance();

        sentry.addNavigationBreadcrumb("activity.main", "here", "there");
        sentry.addHttpBreadcrumb("http://example.com", "GET", 202);

        sentry.captureEvent(sentry.newEventBuilder()
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
        try {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    String s = null;
                    s.length();
                }
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public void onClickBreak(View view) {
        sentry.addBreadcrumb("button.click", "break button");
        crash();

    }

    public void onClickCapture(View view) {
        sentry.addBreadcrumb("button.click", "capture button");
        try {
            crash();
        } catch (Exception e) {
            sentry.captureException(e, "Exception caught in click handler");
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
