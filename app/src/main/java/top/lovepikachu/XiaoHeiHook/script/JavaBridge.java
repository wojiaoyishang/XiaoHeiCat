package top.lovepikachu.XiaoHeiHook.script;

import org.mozilla.javascript.Scriptable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * JavaScript-facing Java bridge.  Java.type(name) returns a JavaClassWrapper that
 * supports static fields, static methods and constructor calls from Rhino.
 */
public final class JavaBridge {
    public final Class<?> BOOLEAN = Boolean.TYPE;
    public final Class<?> BYTE = Byte.TYPE;
    public final Class<?> CHAR = Character.TYPE;
    public final Class<?> SHORT = Short.TYPE;
    public final Class<?> INT = Integer.TYPE;
    public final Class<?> LONG = Long.TYPE;
    public final Class<?> FLOAT = Float.TYPE;
    public final Class<?> DOUBLE = Double.TYPE;
    public final Class<?> VOID = Void.TYPE;

    private final ClassLoader appClassLoader;
    private final Scriptable scope;
    private final Object jsLock;
    private final JavaProxyFactory proxyFactory;
    private final JavaReflectionInvoker invoker;

    public JavaBridge(ClassLoader appClassLoader, Scriptable scope, Object jsLock) {
        this.appClassLoader = appClassLoader == null ? JavaBridge.class.getClassLoader() : appClassLoader;
        this.scope = scope;
        this.jsLock = jsLock == null ? new Object() : jsLock;
        this.proxyFactory = new JavaProxyFactory(this);
        this.invoker = new JavaReflectionInvoker(this);
    }

    ClassLoader getAppClassLoader() { return appClassLoader; }
    Scriptable getScope() { return scope; }
    Object getJsLock() { return jsLock; }
    JavaProxyFactory getProxyFactory() { return proxyFactory; }
    JavaReflectionInvoker getInvoker() { return invoker; }

    public Object type(String className) throws ClassNotFoundException {
        return invoker.wrapClass(invoker.loadClass(className));
    }

    public Object use(String className) throws ClassNotFoundException {
        return type(className);
    }

    public Class<?> classForName(String className) throws ClassNotFoundException {
        return invoker.loadClass(className);
    }

    public Class<?> classForName(String className, boolean initialize, ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(className, initialize, loader == null ? appClassLoader : loader);
    }

    public Method method(Object clazzOrName, String methodName) throws Exception {
        return invoker.findAnyMethod(invoker.asClass(clazzOrName), methodName, -1, false);
    }

    public Method method(Object clazzOrName, String methodName, Object parameterTypes) throws Exception {
        Class<?> clazz = invoker.asClass(clazzOrName);
        Method method = clazz.getDeclaredMethod(methodName, invoker.toClassArray(parameterTypes));
        method.setAccessible(true);
        return method;
    }

    public Constructor<?> constructor(Object clazzOrName) throws Exception {
        return invoker.findAnyConstructor(invoker.asClass(clazzOrName), -1);
    }

    public Constructor<?> constructor(Object clazzOrName, Object parameterTypes) throws Exception {
        Class<?> clazz = invoker.asClass(clazzOrName);
        Constructor<?> constructor = clazz.getDeclaredConstructor(invoker.toClassArray(parameterTypes));
        constructor.setAccessible(true);
        return constructor;
    }

    public Field field(Object clazzOrName, String fieldName) throws Exception {
        Field field = invoker.findField(invoker.asClass(clazzOrName), fieldName);
        field.setAccessible(true);
        return field;
    }

    public Object callStatic(Object clazzOrName, String methodName) throws Exception {
        return callStatic(clazzOrName, methodName, null, null);
    }

    public Object callStatic(Object clazzOrName, String methodName, Object argsObj) throws Exception {
        return callStatic(clazzOrName, methodName, argsObj, null);
    }

    public Object callStatic(Object clazzOrName, String methodName, Object argsObj, Object parameterTypes) throws Exception {
        return invoker.callStatic(clazzOrName, methodName, invoker.toObjectArray(argsObj), parameterTypes);
    }

    public Object call(Object target, String methodName) throws Exception {
        return call(target, methodName, null, null);
    }

    public Object call(Object target, String methodName, Object argsObj) throws Exception {
        return call(target, methodName, argsObj, null);
    }

    public Object call(Object target, String methodName, Object argsObj, Object parameterTypes) throws Exception {
        return invoker.call(target, methodName, invoker.toObjectArray(argsObj), parameterTypes);
    }

    public Object newInstance(Object clazzOrName) throws Exception {
        return newInstance(clazzOrName, null, null);
    }

    public Object newInstance(Object clazzOrName, Object argsObj) throws Exception {
        return newInstance(clazzOrName, argsObj, null);
    }

    public Object newInstance(Object clazzOrName, Object argsObj, Object parameterTypes) throws Exception {
        return invoker.newInstance(clazzOrName, invoker.toObjectArray(argsObj), parameterTypes);
    }

    public Object getStatic(Object clazzOrName, String fieldName) throws Exception {
        return invoker.getStatic(clazzOrName, fieldName);
    }

    public void setStatic(Object clazzOrName, String fieldName, Object value) throws Exception {
        invoker.setStatic(clazzOrName, fieldName, value);
    }

    public Object get(Object target, String fieldName) throws Exception {
        return invoker.get(target, fieldName);
    }

    public void set(Object target, String fieldName, Object value) throws Exception {
        invoker.set(target, fieldName, value);
    }

    public Object newArray(Object componentType, int length) throws Exception {
        return java.lang.reflect.Array.newInstance(invoker.asClass(componentType), length);
    }

    /**
     * Explicitly convert a JS value to a Java type.  The returned value is kept
     * as a Java wrapper so it can be passed back to Java calls without being
     * normalized into a JS primitive.
     */
    public Object to(Object type, Object value) throws Exception {
        return to(type, value, org.mozilla.javascript.Undefined.instance);
    }

    public Object to(Object type, Object value, Object options) throws Exception {
        Class<?> targetType = JavaValueConverter.resolveType(this, type);
        JavaValueConverter.JavaToOptions parsedOptions = JavaValueConverter.JavaToOptions.from(this, options);
        Object javaValue = JavaValueConverter.explicitToJava(this, targetType, value, parsedOptions);
        return invoker.wrapConstructedObject(javaValue);
    }

    public Object proxy(String interfaceName, Object handlers) throws Exception {
        return proxy(invoker.loadClass(interfaceName), handlers);
    }

    public Object proxy(Object interfaceClassOrName, Object handlers) throws Exception {
        return proxyFactory.createProxy(invoker.asClass(interfaceClassOrName), handlers);
    }
}
