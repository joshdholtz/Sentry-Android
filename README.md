# Sentry-Android - Sentry Client for Android
It does what every Sentry client needs to do

Below is an example of how to register Sentry-Android to handle uncaught exceptions

```` java

public class MainActivity extends Activity {

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// Sentry will look for uncaught exceptions from previous runs and send them		
		Sentry.init(this, "YOUR-SENTRY-DSN");

	}

}
		
````

## How To Get Started
- Download the [Sentry-Android JAR]()
- Download the [Protocol JAR](https://github.com/joshdholtz/Protocol-Android/raw/master/builds/protocol-1.0.4.jar)
- Place the JAR in the Android project's "libs" directory
- Code

