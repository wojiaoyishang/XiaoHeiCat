package top.lovepikachu.XiaoHeiHook.script;

import org.mozilla.javascript.Context;

/** Shared Rhino Context configuration for every JavaScript entry point. */
public final class RhinoContextHelper {
    private RhinoContextHelper() {}

    public static void configure(Context cx, String stage) {
        if (cx == null) throw new IllegalArgumentException("Context is null, stage=" + stage);
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
        } catch (Throwable t) {
            throw new IllegalStateException("Rhino ES6 language mode is required, stage=" + stage, t);
        }
        try {
            cx.setOptimizationLevel(-1);
        } catch (Throwable t) {
            throw new IllegalStateException("Rhino interpreted mode configuration failed, stage=" + stage, t);
        }
    }
}
