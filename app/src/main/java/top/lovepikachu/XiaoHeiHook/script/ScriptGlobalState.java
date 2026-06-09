package top.lovepikachu.XiaoHeiHook.script;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide Java-backed state shared by all scripts running in the same target process.
 *
 * Values are intentionally kept as raw Java/runtime objects where possible, so scripts can
 * store objects such as java.lang.reflect.Method, target app instances, ClassLoader objects,
 * and other bridge values that cannot be serialized safely through JS globals or files.
 */
public final class ScriptGlobalState {
    private static final ConcurrentHashMap<String, Object> VALUES = new ConcurrentHashMap<>();
    private static final Object NULL_SENTINEL = new Object();

    private ScriptGlobalState() {}

    public static void set(@NonNull String key, @Nullable Object value) {
        String safeKey = normalizeKey(key);
        Object raw = JavaReflectionInvoker.unwrap(value);
        if (raw == Undefined.instance || raw == Scriptable.NOT_FOUND) raw = null;
        VALUES.put(safeKey, raw == null ? NULL_SENTINEL : raw);
    }

    @Nullable
    public static Object get(@NonNull String key) {
        Object value = VALUES.get(normalizeKey(key));
        return value == NULL_SENTINEL ? null : value;
    }

    public static boolean has(@NonNull String key) {
        return VALUES.containsKey(normalizeKey(key));
    }

    @Nullable
    public static Object remove(@NonNull String key) {
        Object value = VALUES.remove(normalizeKey(key));
        return value == NULL_SENTINEL ? null : value;
    }

    public static int clear() {
        int size = VALUES.size();
        VALUES.clear();
        return size;
    }

    @NonNull
    public static ArrayList<String> keys() {
        return new ArrayList<>(VALUES.keySet());
    }

    public static int size() {
        return VALUES.size();
    }

    @NonNull
    public static LinkedHashMap<String, Object> snapshot() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : VALUES.entrySet()) {
            Object value = entry.getValue();
            out.put(entry.getKey(), value == NULL_SENTINEL ? null : value);
        }
        return out;
    }

    @NonNull
    private static String normalizeKey(@NonNull String key) {
        String safeKey = key.trim();
        if (safeKey.isEmpty()) throw new IllegalArgumentException("global state key is empty");
        return safeKey;
    }
}
