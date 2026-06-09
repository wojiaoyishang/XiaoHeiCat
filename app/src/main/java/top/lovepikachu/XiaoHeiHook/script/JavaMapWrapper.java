package top.lovepikachu.XiaoHeiHook.script;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.util.ArrayList;
import java.util.Map;

/**
 * Scriptable wrapper for Java Map values that must preserve Java identity while
 * still behaving like a plain JS dictionary, e.g. params.data in RPC handlers.
 */
public final class JavaMapWrapper extends ScriptableObject implements Wrapper {
    private final JavaBridge bridge;
    private final Map<Object, Object> target;

    @SuppressWarnings("unchecked")
    JavaMapWrapper(JavaBridge bridge, Map<?, ?> target) {
        this.bridge = bridge;
        this.target = (Map<Object, Object>) target;
        Scriptable scope = bridge.getScope();
        if (scope != null) {
            setParentScope(scope);
            Object proto = ScriptableObject.getObjectPrototype(scope);
            if (proto instanceof Scriptable) setPrototype((Scriptable) proto);
        }
    }

    public Map<Object, Object> getRawMap() { return target; }

    @Override
    public Object unwrap() { return target; }

    @Override
    public String getClassName() { return "JavaMapWrapper"; }

    @Override
    public Object get(String name, Scriptable start) {
        if ("raw".equals(name) || "rawMap".equals(name)) return target;
        if ("getRawMap".equals(name) || "unwrap".equals(name)) return function(name, args -> target);
        if ("toString".equals(name)) return function("toString", args -> String.valueOf(target));
        if ("containsKey".equals(name) || "has".equals(name)) return function(name, args -> args.length > 0 && target.containsKey(normalizeKey(args[0])));
        if ("get".equals(name)) return function("get", args -> args.length == 0 ? Undefined.instance : wrapValue(target.get(normalizeKey(args[0]))));
        if ("put".equals(name) || "set".equals(name)) return function(name, args -> {
            if (args.length < 2) return Undefined.instance;
            Object old = target.put(normalizeKey(args[0]), JavaReflectionInvoker.unwrap(args[1]));
            return wrapValue(old);
        });
        if ("remove".equals(name)) return function("remove", args -> args.length == 0 ? Undefined.instance : wrapValue(target.remove(normalizeKey(args[0]))));
        if ("clear".equals(name)) return function("clear", args -> { target.clear(); return Undefined.instance; });
        if ("keys".equals(name)) return function("keys", args -> bridge.getInvoker().wrapReturn(new ArrayList<>(target.keySet())));
        if ("values".equals(name)) return function("values", args -> bridge.getInvoker().wrapReturn(new ArrayList<>(target.values())));
        if ("entries".equals(name)) return function("entries", args -> {
            ArrayList<Object> rows = new ArrayList<>();
            for (Map.Entry<Object, Object> entry : target.entrySet()) {
                ArrayList<Object> row = new ArrayList<>(2);
                row.add(entry.getKey());
                row.add(entry.getValue());
                rows.add(row);
            }
            return bridge.getInvoker().wrapReturn(rows);
        });
        if ("size".equals(name)) return target.size();
        if (target.containsKey(name)) return wrapValue(target.get(name));
        Object value = super.get(name, start);
        return value == Scriptable.NOT_FOUND ? Scriptable.NOT_FOUND : value;
    }

    @Override
    public Object get(int index, Scriptable start) {
        String key = String.valueOf(index);
        if (target.containsKey(key)) return wrapValue(target.get(key));
        if (target.containsKey(index)) return wrapValue(target.get(index));
        return super.get(index, start);
    }

    @Override
    public void put(String name, Scriptable start, Object value) {
        target.put(name, JavaReflectionInvoker.unwrap(value));
    }

    @Override
    public void put(int index, Scriptable start, Object value) {
        target.put(String.valueOf(index), JavaReflectionInvoker.unwrap(value));
    }

    @Override
    public boolean has(String name, Scriptable start) {
        if ("raw".equals(name) || "rawMap".equals(name) || "getRawMap".equals(name)
                || "unwrap".equals(name) || "toString".equals(name) || "containsKey".equals(name)
                || "has".equals(name) || "get".equals(name) || "put".equals(name) || "set".equals(name)
                || "remove".equals(name) || "clear".equals(name) || "keys".equals(name)
                || "values".equals(name) || "entries".equals(name) || "size".equals(name)) return true;
        return target.containsKey(name) || super.has(name, start);
    }

    @Override
    public boolean has(int index, Scriptable start) {
        return target.containsKey(String.valueOf(index)) || target.containsKey(index) || super.has(index, start);
    }

    @Override
    public void delete(String name) {
        target.remove(name);
    }

    @Override
    public void delete(int index) {
        target.remove(String.valueOf(index));
        target.remove(index);
    }

    @Override
    public Object[] getIds() {
        ArrayList<Object> ids = new ArrayList<>();
        for (Object key : target.keySet()) {
            if (key instanceof Number) ids.add(((Number) key).intValue());
            else ids.add(String.valueOf(key));
        }
        return ids.toArray(new Object[0]);
    }

    @Override
    public Object getDefaultValue(Class<?> hint) {
        if (hint == String.class || hint == null) return String.valueOf(target);
        return super.getDefaultValue(hint);
    }

    @Override
    public String toString() { return String.valueOf(target); }

    private Object wrapValue(Object value) {
        Object raw = JavaReflectionInvoker.unwrap(value);
        if (raw == null || raw == Undefined.instance || raw == Scriptable.NOT_FOUND) return null;
        return bridge.getInvoker().wrapReturn(raw);
    }

    private Object normalizeKey(Object value) {
        Object raw = JavaReflectionInvoker.unwrap(value);
        if (raw == Undefined.instance || raw == Scriptable.NOT_FOUND) return null;
        return raw;
    }

    private BaseFunction function(String name, ThrowingInvoker invoker) {
        BaseFunction fn = new BaseFunction() {
            @Override
            public String getFunctionName() { return name; }

            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    Object value = invoker.invoke(args == null ? new Object[0] : args);
                    return value == null ? Undefined.instance : value;
                } catch (RuntimeException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new RuntimeException("Java map call failed: " + name, t);
                }
            }
        };
        Scriptable scope = bridge.getScope();
        if (scope != null) {
            fn.setParentScope(scope);
            Object proto = ScriptableObject.getFunctionPrototype(scope);
            if (proto instanceof Scriptable) fn.setPrototype((Scriptable) proto);
        }
        return fn;
    }

    private interface ThrowingInvoker { Object invoke(Object[] args) throws Exception; }
}
