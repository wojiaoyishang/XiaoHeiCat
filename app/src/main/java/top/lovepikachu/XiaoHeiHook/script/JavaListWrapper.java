package top.lovepikachu.XiaoHeiHook.script;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.util.List;

/**
 * Scriptable wrapper for Java List values. It keeps the raw Java List available
 * to Java calls while supporting JS-style list[index] and list.length access.
 */
public final class JavaListWrapper extends ScriptableObject implements Wrapper {
    private final JavaBridge bridge;
    private final List<Object> target;

    @SuppressWarnings("unchecked")
    JavaListWrapper(JavaBridge bridge, List<?> target) {
        this.bridge = bridge;
        this.target = (List<Object>) target;
        Scriptable scope = bridge.getScope();
        if (scope != null) {
            setParentScope(scope);
            Object proto = ScriptableObject.getArrayPrototype(scope);
            if (proto instanceof Scriptable) setPrototype((Scriptable) proto);
        }
    }

    public List<Object> getRawList() { return target; }

    @Override
    public Object unwrap() { return target; }

    @Override
    public String getClassName() { return "JavaListWrapper"; }

    @Override
    public Object get(String name, Scriptable start) {
        if ("raw".equals(name) || "rawList".equals(name)) return target;
        if ("getRawList".equals(name) || "unwrap".equals(name)) return function(name, args -> target);
        if ("toString".equals(name)) return function("toString", args -> String.valueOf(target));
        if ("length".equals(name) || "size".equals(name)) return target.size();
        if ("get".equals(name)) return function("get", args -> args.length == 0 ? Undefined.instance : get(toIndex(args[0]), this));
        if ("set".equals(name)) return function("set", args -> {
            if (args.length < 2) return Undefined.instance;
            int index = toIndex(args[0]);
            Object old = target.set(index, JavaReflectionInvoker.unwrap(args[1]));
            return wrapValue(old);
        });
        if ("add".equals(name) || "push".equals(name)) return function(name, args -> {
            for (Object arg : args) target.add(JavaReflectionInvoker.unwrap(arg));
            return target.size();
        });
        if ("remove".equals(name)) return function("remove", args -> args.length == 0 ? Undefined.instance : wrapValue(target.remove(toIndex(args[0]))));
        if ("clear".equals(name)) return function("clear", args -> { target.clear(); return Undefined.instance; });
        Object value = super.get(name, start);
        return value == Scriptable.NOT_FOUND ? Scriptable.NOT_FOUND : value;
    }

    @Override
    public Object get(int index, Scriptable start) {
        if (index >= 0 && index < target.size()) return wrapValue(target.get(index));
        return Scriptable.NOT_FOUND;
    }

    @Override
    public void put(int index, Scriptable start, Object value) {
        if (index < 0) return;
        while (target.size() <= index) target.add(null);
        target.set(index, JavaReflectionInvoker.unwrap(value));
    }

    @Override
    public boolean has(String name, Scriptable start) {
        if ("raw".equals(name) || "rawList".equals(name) || "getRawList".equals(name)
                || "unwrap".equals(name) || "toString".equals(name) || "length".equals(name)
                || "size".equals(name) || "get".equals(name) || "set".equals(name)
                || "add".equals(name) || "push".equals(name) || "remove".equals(name)
                || "clear".equals(name)) return true;
        return super.has(name, start);
    }

    @Override
    public boolean has(int index, Scriptable start) {
        return index >= 0 && index < target.size();
    }

    @Override
    public void delete(int index) {
        if (index >= 0 && index < target.size()) target.remove(index);
    }

    @Override
    public Object[] getIds() {
        Object[] ids = new Object[target.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = i;
        return ids;
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

    private int toIndex(Object value) {
        Object raw = JavaReflectionInvoker.unwrap(value);
        if (raw instanceof Number) return ((Number) raw).intValue();
        return Integer.parseInt(String.valueOf(raw));
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
                    throw new RuntimeException("Java list call failed: " + name, t);
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
