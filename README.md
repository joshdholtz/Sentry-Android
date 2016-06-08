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
		setContentView(R.layout.activity_main);

		// Sentry will look for uncaught exceptions from previous runs and send them		
		Sentry.init(this.getApplicationContext(), "YOUR-SENTRY-DSN");

	}

}
```

### Updates

Version | Changes
--- | ---
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
compile 'com.joshdholtz.sentry:sentry-android:1.4.1'
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
		try {
			builder.getExtra().put("wifi", String.valueOf(mWifi.isConnected()));
			builder.getTags().put("tag_1", "value_1");
		} catch (JSONException e) {}
		
		return builder;
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

Sentry-Android is available under the MIT license. See the LICENSE file for more info.
