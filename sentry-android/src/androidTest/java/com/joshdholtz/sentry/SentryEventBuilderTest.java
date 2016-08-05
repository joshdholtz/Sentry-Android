package com.joshdholtz.sentry;

import android.provider.Telephony;

import com.google.common.base.Joiner;

import junit.framework.TestCase;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Pattern;

public class SentryEventBuilderTest extends TestCase {

    // Since regexes are very hard to read, we build the regex to recognize an internal package
    // name programatically.
    // Given a list of packages, return a regex to match them.
    private String toPackageRegex(String... packages) {
        return String.format("^(%s)\\..*", Joiner.on("|").join(packages).replace(".", "\\."));
    }


    // Test both sides of in_app
    public void testStackTraces() throws JSONException {
        final StackTraceElement internal = new StackTraceElement("com.google.android.gms.Common.Foo", "bar", "Foo.java", 19);
        {
            JSONObject json = Sentry.SentryEventBuilder.frameJson(internal);
            assertEquals(false, json.getBoolean("in_app"));
            assertEquals(19, json.getInt("lineno"));
            assertEquals("com.google.android.gms.Common.Foo", json.getString("module"));
            assertEquals("bar", json.getString("function"));
            assertEquals("Foo.java", json.getString("filename"));
        }

        final StackTraceElement user = new StackTraceElement("github.sentry.Common.Foo", "qux", "Foo.scala", 22);
        {
            JSONObject json = Sentry.SentryEventBuilder.frameJson(user);
            assertEquals(true, json.getBoolean("in_app"));
            assertEquals(22, json.getInt("lineno"));
            assertEquals("github.sentry.Common.Foo", json.getString("module"));
            assertEquals("qux", json.getString("function"));
            assertEquals("Foo.scala", json.getString("filename"));
        }
    }

    public void testInternalPackageNameRegex() throws Exception {

        // Test 2 simple cases.
        assertEquals("^(com\\.example)\\..*", toPackageRegex("com.example"));
        assertEquals("^(com\\.example\\.www|com\\.example)\\..*", toPackageRegex("com.example.www", "com.example"));

        // Now test the actual list of packages that we want to ensure we keep correct.
        final String[] internalPackages = {
                "java",
                "android",
                "com.android",
                "com.google.android",
                "dalvik.system"};
        assertEquals(Sentry.SentryEventBuilder.isInternalPackage, toPackageRegex(internalPackages));


        final String[] internalClasses = {
                "java.util.list",
                "android.build.version",
                "com.android.calculator",
                "com.google.android.gms.Location",
                "dalvik.system.console"
        };
        for (String c : internalClasses) {
            assertTrue(c, c.matches(Sentry.SentryEventBuilder.isInternalPackage));
        }

        final String[] userClasses= {
                "sentry.client.stack",
                "com.example.widget",
        };

        for (String c : userClasses) {
            assertFalse(c, c.matches(Sentry.SentryEventBuilder.isInternalPackage));
        }
    }
}