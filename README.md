# Sentry-Android - Sentry Client for Android
It does what every Sentry client needs to do

Below is an example of how to register Sentry-Android to handle uncaught exceptions

```xml
<!-- REQUIRED to send captures to Sentry -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- OPTIONAL but makes Sentry-Android smarter -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

``` java
public class MainActivity extends Activity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Sentry will look for uncaught exceptions from previous runs and send them
        Sentry.init(this, "YOUR-SENTRY-DSN");
    }
}
```

### Updates

Version | Changes
--- | ---
**1.5.1** | Revert accidental API removal of `captureException(Throwable, SentryEventLevel)`.
**1.5.0** | Add Breadcrumb support [#70](https://github.com/joshdholtz/Sentry-Android/pull/70).<br/>Add release tracking by default [#78](https://github.com/joshdholtz/Sentry-Android/pull/78).<br/>Add the ability to attach a stack-trace to any event [#81](https://github.com/joshdholtz/Sentry-Android/issues/81).<br/>Use a fixed-size thread-pool for sending events [#80](https://github.com/joshdholtz/Sentry-Android/pull/80).<br/>Make it easier to add a message when capturing an exception [#77](https://github.com/joshdholtz/Sentry-Android/pull/77).<br/>Added helper methods for addExtra and addTag [#74](https://github.com/joshdholtz/Sentry-Android/pull/74).<br/>(thanks to [marcomorain](https://github.com/marcomorain))
**1.4.4** | Sends up device, app, and OS context by default (thanks to [marcomorain](https://github.com/marcomorain))
**1.4.3** | Fixes for a Google Play warning and added option to not use crash reporting (thanks to [ZeroStride](https://github.com/ZeroStride))
**1.4.1** | Fixes for a potential memory leak and a crash (thanks to [Syhids](https://github.com/Syhids) and [woostrowski](https://github.com/woostrowski))
**1.4.0** | Fixes issues when using self-hosted Sentry server
**1.2.1** | Sends up data to Sentry as UTF-8
**1.2.0** | Added support for Android version 23 and made library avaiable to install via gradle
**1.1.4** | Added support for verify_ssl on DSN (thanks [Kras4ooo](https://github.com/Kras4ooo))
**1.1.3** | Exceptions appear super mega awesome in Sentry now (thanks [doapp-jeremiah](https://github.com/doapp-jeremiah))
**1.1.2** | Bug fixed - Setting a `captureListener` was required to send a report (thanks [mathzol](https://github.com/mathzol))
**1.1.1** | Uncaught exception handler now calls SentryEventCaptureListener
**1.1.0** | Saves requests that were captured offline or failed and tries to resend them when it can
**1.0.0** | Removed dependency to `Protocol`; allows capture of message from background thread
**0.1.0** | Initial release

## How To Get Started

### Gradle
Available in jCenter
```
compile 'com.joshdholtz.sentry:sentry-android:1.5.0'
```

### Manual
JAR can be downloaded [here](https://bintray.com/joshdholtz/maven/sentry-android/view#files/com/joshdholtz/sentry/sentry-android/1.2.1)

## This Is How We Do It

### Permissions in manifest

The AndroidManifest.xml requires the permission `android.permission.INTERNET` and would like the permission `android.permission.ACCESS_NETWORK_STATE` even though optional.

```xml
<!-- REQUIRED to send captures to Sentry -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- OPTIONAL but makes Sentry-Android smarter -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### Capture a message
``` java
Sentry.captureMessage("Something significant may have happened");
```

### Capture a caught exception
``` java
try {
	JSONObject obj = new JSONObject();
} catch (JSONException e) {
	Sentry.captureException(e);
}
```

### Capture custom event
``` java
Sentry.captureEvent(new Sentry.SentryEventBuilder()
	.setMessage("Being awesome")
	.setCulprit("Josh Holtz")
	.setTimestamp(System.currentTimeMillis())
);
```

### Capture Breadcrumbs
You can record [breadcrumbs](https://docs.sentry.io/hosted/learn/breadcrumbs/) to
track what happened in your application leading up to an error.

There are 3 ways to log a breadcrumb.

```java
// Record that a user sent a HTTP POST to example.com and it was successful.
Sentry.addHttpBreadcrumb("http://example.com", "POST", 200);

// Record the fact that user clicked a button to go from the main menu to the
// settings menu.
Sentry.addNavigationBreadcrumb("user.click", "main menu", "settings");

// Record a general,  application specific event
Sentry.addBreadcrumb("user.state_change", "logged in");
```


### Release Tracking

The SDK will automatically tag events with [a release](https://docs.sentry.io/hosted/api/releases/post-project-releases/).
The release is set to the app's [`versionCode` by default](https://developer.android.com/studio/publish/versioning.html#appversioning).
You can override the `release` easily by using the `setRelease(String release)`
function from inside a `SentryEventCaptureListener`.

### Set a listener to intercept the SentryEventBuilder before each capture
``` java
// CALL THIS BEFORE CALLING Sentry.init
// Sets a listener to intercept the SentryEventBuilder before
// each capture to set values that could change state
Sentry.setCaptureListener(new SentryEventCaptureListener() {

    @Override
    public SentryEventBuilder beforeCapture(SentryEventBuilder builder) {

        // Needs permission - <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
        ConnectivityManager connManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        // Sets extra key if wifi is connected
        return builder
            .addExtra("wifi", String.valueOf(mWifi.isConnected()));
            .addTag("tag_1", "value_1");
    }
});

```

## Use for self hosted Sentry

### Init with your base url
``` java
Sentry.init(this, "http://your-base-url.com" "YOUR-SENTRY-DSN");

```

## Contact

Email: [josh@rokkincat.com](mailto:josh@rokkincat.com)<br/>
Twitter: [@joshdholtz](http://twitter.com/joshdholtz)

## License

Sentry-Android is available under the [MIT license](LICENSE).
