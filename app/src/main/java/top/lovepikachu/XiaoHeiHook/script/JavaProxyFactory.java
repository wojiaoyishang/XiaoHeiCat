package top.lovepikachu.XiaoHeiHook.script;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Creates explicit Java.proxy(...) objects and implicit SAM proxies. */
public final class JavaProxyFactory {
    private final JavaBridge bridge;

    JavaProxyFactory(JavaBridge bridge) {
        this.bridge = bridge;
    }

    public Object createProxy(Class<?> interfaceType, Object handlerObject) {
        if (interfaceType == null || !interfaceType.isInterface()) {
            throw new IllegalArgumentException("Java.proxy requires an interface class: " + interfaceType);
        }
        Object unwrapped = JavaReflectionInvoker.unwrap(handlerObject);
        Method sam = findSingleAbstractMethod(interfaceType);
        if (unwrapped instanceof Function && sam == null) {
            throw new IllegalArgumentException("JS function can only implement a single-abstract-method interface: " + interfaceType.getName());
        }
        InvocationHandler handler = new JsInvocationHandler(interfaceType, handlerObject, sam);
        ClassLoader loader = interfaceType.getClassLoader();
        if (loader == null) loader = bridge.getAppClassLoader();
        return Proxy.newProxyInstance(loader, new Class<?>[]{interfaceType}, handler);
    }

    boolean canAutoProxy(Class<?> interfaceType, Object value) {
        if (interfaceType == null || !interfaceType.isInterface()) return false;
        Object unwrapped = JavaReflectionInvoker.unwrap(value);
        if (unwrapped == null) return false;
        if (interfaceType.isInstance(unwrapped)) return true;
        if (unwrapped instanceof Function) return findSingleAbstractMethod(interfaceType) != null;
        return unwrapped instanceof Scriptable;
    }

    Object maybeCreateProxy(Class<?> interfaceType, Object value) {
        Object unwrapped = JavaReflectionInvoker.unwrap(value);
        if (unwrapped == null || interfaceType.isInstance(unwrapped)) return unwrapped;
        if (canAutoProxy(interfaceType, value)) return createProxy(interfaceType, value);
        return unwrapped;
    }

    Method findSingleAbstractMethod(Class<?> interfaceType) {
        ArrayList<Method> methods = new ArrayList<>();
        collectAbstractMethods(interfaceType, methods, new LinkedHashMap<String, Boolean>());
        return methods.size() == 1 ? methods.get(0) : null;
    }

    private void collectAbstractMethods(Class<?> type, ArrayList<Method> out, Map<String, Boolean> seen) {
        for (Method method : type.getMethods()) {
            if (!isInterfaceCallbackMethod(method)) continue;
            String sig = signature(method);
            if (!seen.containsKey(sig)) {
                seen.put(sig, Boolean.TRUE);
                out.add(method);
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!isInterfaceCallbackMethod(method)) continue;
            String sig = signature(method);
            if (!seen.containsKey(sig)) {
                seen.put(sig, Boolean.TRUE);
                out.add(method);
            }
        }
    }

    private boolean isInterfaceCallbackMethod(Method method) {
        if (method == null) return false;
        if (method.getDeclaringClass() == Object.class) return false;
        int modifiers = method.getModifiers();
        if (Modifier.isStatic(modifiers)) return false;
        return Modifier.isAbstract(modifiers);
    }

    private String signature(Method method) {
        StringBuilder sb = new StringBuilder(method.getName()).append('(');
        for (Class<?> type : method.getParameterTypes()) sb.append(type.getName()).append(';');
        return sb.append(')').toString();
    }

    private final class JsInvocationHandler implements InvocationHandler {
        private final Class<?> interfaceType;
        private final Object handlerObject;
        private final Method sam;

        JsInvocationHandler(Class<?> interfaceType, Object handlerObject, Method sam) {
            this.interfaceType = interfaceType;
            this.handlerObject = handlerObject;
            this.sam = sam;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                String name = method.getName();
                if ("toString".equals(name)) return "[JavaProxy " + interfaceType.getName() + "]";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == (args == null || args.length == 0 ? null : args[0]);
            }

            Function function = resolveFunction(method);
            if (function == null) return bridge.getInvoker().defaultValue(method.getReturnType());
            Object result = invokeJsFunction(function, method, args == null ? new Object[0] : args);
            if (method.getReturnType() == Void.TYPE) return null;
            return bridge.getInvoker().convertArgument(method.getReturnType(), result);
        }

        private Function resolveFunction(Method method) {
            Object unwrapped = JavaReflectionInvoker.unwrap(handlerObject);
            if (unwrapped instanceof Function) {
                if (sam != null && signature(sam).equals(signature(method))) return (Function) unwrapped;
                return null;
            }
            if (unwrapped instanceof Scriptable) {
                Object value = ScriptableObject.getProperty((Scriptable) unwrapped, method.getName());
                if (value instanceof Function) return (Function) value;
                Object alt = ScriptableObject.getProperty((Scriptable) unwrapped, signature(method));
                if (alt instanceof Function) return (Function) alt;
            }
            return null;
        }

        private Object invokeJsFunction(Function function, Method method, Object[] javaArgs) throws Throwable {
            synchronized (bridge.getJsLock()) {
                ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
                Context cx = Context.enter();
                try {
                    Thread.currentThread().setContextClassLoader(bridge.getAppClassLoader());
                    RhinoContextHelper.configure(cx, "java-proxy:" + interfaceType.getName() + "." + method.getName());
                    Scriptable scope = bridge.getScope();
                    Object[] jsArgs = new Object[javaArgs.length];
                    for (int i = 0; i < javaArgs.length; i++) {
                        Object wrapped = bridge.getInvoker().wrapReturn(javaArgs[i]);
                        jsArgs[i] = wrapped instanceof Scriptable ? wrapped : Context.javaToJS(wrapped, scope);
                    }
                    Scriptable thisObj = handlerObject instanceof Scriptable ? (Scriptable) handlerObject : scope;
                    Object result = function.call(cx, scope, thisObj, jsArgs);
                    return result == Undefined.instance ? null : result;
                } finally {
                    Thread.currentThread().setContextClassLoader(oldClassLoader);
                    Context.exit();
                }
            }
        }
    }
}
