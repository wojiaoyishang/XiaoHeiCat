package top.lovepikachu.XiaoHeiHook.debug;

import android.util.Log;

public final class JsDebugTrace {
    public static final String TAG = "XHH-JSDBG";

    /** Enable/disable all line-debug diagnostic logs. */
    public static volatile boolean ENABLED = true;

    /** onLineChange can be noisy; keep it true while diagnosing line breakpoints. */
    public static volatile boolean TRACE_LINES = true;

    private JsDebugTrace() {}

    public static void d(String msg) {
        if (ENABLED) Log.d(TAG, msg);
    }

    public static void i(String msg) {
        if (ENABLED) Log.i(TAG, msg);
    }

    public static void w(String msg) {
        if (ENABLED) Log.w(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        if (ENABLED) Log.e(TAG, msg, t);
    }

    public static String safe(Object value) {
        try {
            return String.valueOf(value);
        } catch (Throwable t) {
            return "<toString failed: " + t.getClass().getName() + ">";
        }
    }
}
