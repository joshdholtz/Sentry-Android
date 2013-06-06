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
		Sentry.init(this, "https://5fdb6343bf324770b6085f1b17ef030f:b9b52af111f5420f9adf262bd6f88267@app.getsentry.com/7857");

	}

}
		
````

## How To Get Started
- Download the [Sentry-Android JAR]()
- Place the JAR in the Android project's "libs" directory
- Code

