package top.lovepikachu.XiaoHeiHook.script;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Scriptable wrapper for ordinary Java instances returned from Java Bridge calls. */
public final class JavaObjectWrapper extends ScriptableObject implements Wrapper {
    private final JavaBridge bridge;
    private final Object target;
    private final Class<?> explicitType;

    JavaObjectWrapper(JavaBridge bridge, Object target) {
        this(bridge, target, null);
    }

    JavaObjectWrapper(JavaBridge bridge, Object target, Class<?> explicitType) {
        this.bridge = bridge;
        this.target = target;
        this.explicitType = explicitType;
        Scriptable scope = bridge.getScope();
        if (scope != null) {
            setParentScope(scope);
            Object proto = ScriptableObject.getObjectPrototype(scope);
            if (proto instanceof Scriptable) setPrototype((Scriptable) proto);
        }
    }

    public Object getRawObject() { return target; }

    /**
     * Non-null only for values produced by Java.to(type, value[, options]).
     * Hook return conversion uses this to respect the user's explicit type
     * instead of re-inferring the value from the hooked method return type.
     */
    public Class<?> getExplicitType() { return explicitType; }

    public boolean hasExplicitType() { return explicitType != null; }

    @Override
    public Object unwrap() { return target; }

    @Override
    public String getClassName() { return "JavaObjectWrapper"; }

    @Override
    public Object get(String name, Scriptable start) {
        if ("raw".equals(name) || "rawObject".equals(name)) return target;
        if ("getRawObject".equals(name) || "unwrap".equals(name)) return function(name, args -> target);
        if ("toString".equals(name)) return function("toString", args -> String.valueOf(target));
        if ("getClass".equals(name)) return function("getClass", args -> bridge.getInvoker().wrapClass(target.getClass()));

        try {
            Field field = bridge.getInvoker().findField(target.getClass(), name);
            if (!Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                return bridge.getInvoker().wrapReturn(field.get(target));
            }
        } catch (NoSuchFieldException ignored) {
        } catch (Throwable t) {
            throw new RuntimeException("Read field failed: " + target.getClass().getName() + "." + name, t);
        }

        if (bridge.getInvoker().hasMethod(target.getClass(), name, false)) {
            return function(name, args -> bridge.getInvoker().call(target, name, args, null));
        }
        Object value = super.get(name, start);
        return value == Scriptable.NOT_FOUND ? Scriptable.NOT_FOUND : value;
    }

    @Override
    public void put(String name, Scriptable start, Object value) {
        try {
            Field field = bridge.getInvoker().findField(target.getClass(), name);
            if (!Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                field.set(target, bridge.getInvoker().convertArgument(field.getType(), value));
                return;
            }
        } catch (NoSuchFieldException ignored) {
        } catch (Throwable t) {
            throw new RuntimeException("Write field failed: " + target.getClass().getName() + "." + name, t);
        }
        super.put(name, start, value);
    }

    @Override
    public boolean has(String name, Scriptable start) {
        if ("raw".equals(name) || "rawObject".equals(name) || "getRawObject".equals(name)
                || "unwrap".equals(name) || "toString".equals(name) || "getClass".equals(name)) return true;
        try {
            Field field = bridge.getInvoker().findField(target.getClass(), name);
            if (!Modifier.isStatic(field.getModifiers())) return true;
        } catch (Throwable ignored) {
        }
        return bridge.getInvoker().hasMethod(target.getClass(), name, false) || super.has(name, start);
    }

    @Override
    public Object getDefaultValue(Class<?> hint) {
        if (hint == String.class || hint == null) return String.valueOf(target);
        return super.getDefaultValue(hint);
    }

    @Override
    public String toString() { return String.valueOf(target); }

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
                    throw new RuntimeException("Java instance call failed: " + target.getClass().getName() + "." + name, t);
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
