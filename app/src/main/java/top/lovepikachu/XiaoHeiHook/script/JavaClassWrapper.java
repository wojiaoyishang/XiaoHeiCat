package top.lovepikachu.XiaoHeiHook.script;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Wrapper returned by Java.type("..."). */
public final class JavaClassWrapper extends BaseFunction implements Wrapper {
    private final JavaBridge bridge;
    private final Class<?> rawClass;

    JavaClassWrapper(JavaBridge bridge, Class<?> rawClass) {
        this.bridge = bridge;
        this.rawClass = rawClass;
        Scriptable scope = bridge.getScope();
        if (scope != null) {
            setParentScope(scope);
            Object proto = ScriptableObject.getFunctionPrototype(scope);
            if (proto instanceof Scriptable) setPrototype((Scriptable) proto);
        }
    }

    public Class<?> getRawClass() { return rawClass; }
    public Class<?> getClassObject() { return rawClass; }
    public Class<?> classObject() { return rawClass; }

    @Override
    public Object unwrap() { return rawClass; }

    @Override
    public String getClassName() { return "JavaClassWrapper"; }

    @Override
    public Object call(Context cx, Scriptable callScope, Scriptable thisObj, Object[] args) {
        try {
            return bridge.getInvoker().newInstance(rawClass, args == null ? new Object[0] : args, null);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Java constructor call failed: " + rawClass.getName(), t);
        }
    }

    @Override
    public Scriptable construct(Context cx, Scriptable callScope, Object[] args) {
        Object value = call(cx, callScope, this, args);
        if (value instanceof Scriptable) return (Scriptable) value;
        throw new IllegalStateException("Constructor did not return a scriptable Java object: " + rawClass.getName());
    }

    @Override
    public Object get(String name, Scriptable start) {
        if ("classObject".equals(name) || "rawClass".equals(name) || "raw".equals(name)) return rawClass;
        if ("getRawClass".equals(name)) return function("getRawClass", args -> rawClass);
        if ("toString".equals(name)) return function("toString", args -> toString());
        if ("getName".equals(name)) return function("getName", args -> rawClass.getName());
        if ("name".equals(name) || "className".equals(name)) return rawClass.getName();
        if ("prototype".equals(name)) return super.get(name, start);

        try {
            Field field = bridge.getInvoker().findField(rawClass, name);
            if (Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                return bridge.getInvoker().wrapReturn(field.get(null));
            }
        } catch (NoSuchFieldException ignored) {
        } catch (Throwable t) {
            throw new RuntimeException("Read static field failed: " + rawClass.getName() + "." + name, t);
        }

        if (bridge.getInvoker().hasMethod(rawClass, name, true)) {
            return function(name, args -> bridge.getInvoker().callStatic(rawClass, name, args, null));
        }

        // Compatibility: Java.type() now returns a wrapper, but many existing
        // scripts still use Class APIs directly, e.g.
        // Application.getDeclaredMethod("attach", ContextClass).  When no static
        // member on the target class matches, forward java.lang.Class instance
        // methods to the wrapped Class object.
        if (bridge.getInvoker().hasMethod(Class.class, name, false)) {
            return function(name, args -> bridge.getInvoker().callInstance(rawClass, name, args, null));
        }
        Object value = super.get(name, start);
        return value == Scriptable.NOT_FOUND ? Scriptable.NOT_FOUND : value;
    }

    @Override
    public boolean has(String name, Scriptable start) {
        if ("classObject".equals(name) || "rawClass".equals(name) || "raw".equals(name)
                || "getRawClass".equals(name) || "toString".equals(name) || "getName".equals(name)
                || "name".equals(name) || "className".equals(name)) return true;
        try {
            Field field = bridge.getInvoker().findField(rawClass, name);
            if (Modifier.isStatic(field.getModifiers())) return true;
        } catch (Throwable ignored) {
        }
        return bridge.getInvoker().hasMethod(rawClass, name, true)
                || bridge.getInvoker().hasMethod(Class.class, name, false)
                || super.has(name, start);
    }

    @Override
    public Object getDefaultValue(Class<?> hint) {
        if (hint == String.class || hint == null) return toString();
        return super.getDefaultValue(hint);
    }

    @Override
    public String toString() {
        return "[JavaClass " + rawClass.getName() + "]";
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
                    throw new RuntimeException("Java static call failed: " + rawClass.getName() + "." + name, t);
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
