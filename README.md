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
**1.1.1** | Uncaught exception handler now calls SentryEventCaptureListener
**1.1.0** | Saves requests that were captured offline or failed and tries to resend them when it can
**1.0.0** | Removed dependency to `Protocol`; allows capture of message from background thread
**0.1.0** | Initial release

## How To Get Started
- Download the [Sentry-Android JAR - v1.1.1](https://github.com/joshdholtz/Sentry-Android/releases/tag/v1.1.1)
- Place the JAR in the Android project's "libs" directory
- Code

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
	JSONObject obj = new JSONObjet();
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
