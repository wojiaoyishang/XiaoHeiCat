package top.lovepikachu.XiaoHeiHook.script;

import androidx.annotation.Nullable;

import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
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
        Scriptable scope = currentTopScope();
        if (scope != null) {
            try {
                Object obj = Context.getCurrentContext().newObject(scope);
                if (obj instanceof NativeObject) return (NativeObject) obj;
            } catch (Throwable ignored) {
                // Fall through to the scope-less fallback below.
            }
        }
        NativeObject object = new NativeObject();
        attachScope(object, scope, false);
        return object;
    }

    private static NativeArray newArray(Object[] values) {
        Scriptable scope = currentTopScope();
        if (scope != null) {
            try {
                return (NativeArray) Context.getCurrentContext().newArray(scope, values);
            } catch (Throwable ignored) {
                // Fall through to the scope-less fallback below.
            }
        }
        NativeArray array = new NativeArray(values);
        attachScope(array, scope, true);
        return array;
    }

    /**
     * Rhino NativeObject/NativeArray must be created with the current top-level scope/prototype.
     * Otherwise ordinary JS operations such as String(ret.paths), "" + ret.paths,
     * ret.paths.join(","), or Array.prototype methods may throw "Cannot find default value",
     * even though ret.paths[0] can be read. API facades should normalize Java Map/List results
     * into real scoped JS objects/arrays, so scripts do not need arrayLike/getAny compatibility
     * helpers for API return values.
     */
    @Nullable
    private static Scriptable currentTopScope() {
        try {
            Context cx = Context.getCurrentContext();
            if (cx == null) return null;
            Scriptable scope = ScriptRuntime.getTopCallScope(cx);
            return scope == null ? null : ScriptableObject.getTopLevelScope(scope);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void attachScope(Scriptable object, @Nullable Scriptable scope, boolean array) {
        if (scope == null || object == null) return;
        try {
            object.setParentScope(scope);
            object.setPrototype(array
                    ? ScriptableObject.getClassPrototype(scope, "Array")
                    : ScriptableObject.getObjectPrototype(scope));
        } catch (Throwable ignored) {
            // A scope-less value is still better than failing the API call.
        }
    }

    private static void put(NativeObject object, String key, Object value) {
        object.put(key, object, value == Undefined.instance || value == Scriptable.NOT_FOUND ? null : value);
    }

    private static Object unwrap(Object value) {
        if (value instanceof Wrapper) return ((Wrapper) value).unwrap();
        return value;
    }
}