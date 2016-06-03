package org.mozilla.gecko;

import android.content.Context;

/**
 * Mock class for CodecProxy to get {@link Context} for service connection.
 */
public class GeckoAppShell {
    private static Context sAppContext;

    public static void setAppContext(Context ctxt) { sAppContext = ctxt; }
    public static Context getApplicationContext() { return sAppContext; }
}
