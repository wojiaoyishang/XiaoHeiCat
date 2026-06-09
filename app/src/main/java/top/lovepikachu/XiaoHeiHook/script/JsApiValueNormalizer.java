package top.lovepikachu.XiaoHeiHook.script;

import androidx.annotation.Nullable;

import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Executable;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts Java-side API values to Rhino-stable JavaScript values before returning them to scripts.
 *
 * Public JS facades should return values through this helper whenever they expose nested Map/List
 * structures.  That keeps script code on plain property/array access such as ret.paths[0] instead
 * of Java collection chains such as ret.get("sources").get(i).get("path").
 */
public final class JsApiValueNormalizer {
    private static final int MAX_DEPTH = 12;

    private JsApiValueNormalizer() {}

    public static Object toJs(@Nullable Object value) {
        return toJs(value, 0, new IdentityHashMap<Object, Boolean>());
    }

    @SuppressWarnings("unchecked")
    private static Object toJs(@Nullable Object value, int depth, IdentityHashMap<Object, Boolean> seen) {
        Object v = unwrap(value);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND || v == JSONObject.NULL) return null;
        if (v instanceof CharSequence || v instanceof Number || v instanceof Boolean) return v;
        if (v instanceof Scriptable) return v;
        if (depth > MAX_DEPTH) return String.valueOf(v);

        if (v instanceof Throwable) {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            Throwable t = (Throwable) v;
            map.put("type", t.getClass().getName());
            map.put("message", t.getMessage());
            map.put("text", String.valueOf(t));
            return toJs(map, depth + 1, seen);
        }
        if (v instanceof File) return ((File) v).getAbsolutePath();
        if (v instanceof Class<?>) return ((Class<?>) v).getName();
        if (v instanceof ClassLoader || v instanceof Executable) return String.valueOf(v);

        if (seen.containsKey(v)) return String.valueOf(v);
        seen.put(v, Boolean.TRUE);
        try {
            if (v instanceof JSONObject) {
                JSONObject obj = (JSONObject) v;
                NativeObject out = newObject();
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    put(out, key, toJs(obj.opt(key), depth + 1, seen));
                }
                return out;
            }
            if (v instanceof JSONArray) {
                JSONArray arr = (JSONArray) v;
                Object[] out = new Object[arr.length()];
                for (int i = 0; i < arr.length(); i++) out[i] = toJs(arr.opt(i), depth + 1, seen);
                return newArray(out);
            }
            if (v instanceof Map) {
                NativeObject out = newObject();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) v).entrySet()) {
                    Object keyObj = entry.getKey();
                    if (keyObj == null) continue;
                    put(out, String.valueOf(keyObj), toJs(entry.getValue(), depth + 1, seen));
                }
                return out;
            }
            if (v instanceof Iterable) {
                java.util.ArrayList<Object> items = new java.util.ArrayList<>();
                for (Object item : (Iterable<?>) v) items.add(toJs(item, depth + 1, seen));
                return newArray(items.toArray(new Object[0]));
            }
            Class<?> cls = v.getClass();
            if (cls.isArray()) {
                int len = Array.getLength(v);
                Object[] out = new Object[len];
                for (int i = 0; i < len; i++) out[i] = toJs(Array.get(v, i), depth + 1, seen);
                return newArray(out);
            }
            return v;
        } finally {
            seen.remove(v);
        }
    }

    private static NativeObject newObject() {
        NativeObject object = new NativeObject();
        attachScope(object);
        return object;
    }

    private static NativeArray newArray(Object[] values) {
        NativeArray array = new NativeArray(values);
        attachScope(array);
        return array;
    }

    private static void attachScope(Scriptable object) {
        // NativeObject/NativeArray keep own properties even without attaching a parent scope.
        // Avoid depending on Rhino internals here; the returned value is still stable for
        // property access (ret.paths) and array access (ret.paths[0]).
    }

    private static void put(NativeObject object, String key, Object value) {
        object.put(key, object, value == Undefined.instance || value == Scriptable.NOT_FOUND ? null : value);
    }

    private static Object unwrap(Object value) {
        if (value instanceof Wrapper) return ((Wrapper) value).unwrap();
        return value;
    }
}
