package top.lovepikachu.XiaoHeiHook.script;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Centralized overload resolution, argument conversion and Java value wrapping. */
public final class JavaReflectionInvoker {
    private final JavaBridge bridge;

    JavaReflectionInvoker(JavaBridge bridge) {
        this.bridge = bridge;
    }

    public Class<?> loadClass(String className) throws ClassNotFoundException {
        if (className == null) throw new ClassNotFoundException("null");
        switch (className) {
            case "void": return Void.TYPE;
            case "boolean": return Boolean.TYPE;
            case "byte": return Byte.TYPE;
            case "char": return Character.TYPE;
            case "short": return Short.TYPE;
            case "int": return Integer.TYPE;
            case "long": return Long.TYPE;
            case "float": return Float.TYPE;
            case "double": return Double.TYPE;
            default:
                try {
                    return Class.forName(className, false, bridge.getAppClassLoader());
                } catch (ClassNotFoundException ignored) {
                    return Class.forName(className);
                }
        }
    }

    public Class<?> asClass(Object value) throws ClassNotFoundException {
        Object unwrapped = unwrap(value);
        if (unwrapped instanceof Class<?>) return (Class<?>) unwrapped;
        if (unwrapped instanceof JavaClassWrapper) return ((JavaClassWrapper) unwrapped).getRawClass();
        return loadClass(String.valueOf(unwrapped));
    }

    public Class<?>[] toClassArray(Object value) throws ClassNotFoundException {
        Object unwrapped = unwrap(value);
        if (unwrapped == null || unwrapped == Scriptable.NOT_FOUND || unwrapped == Undefined.instance) return new Class<?>[0];
        ArrayList<Class<?>> classes = new ArrayList<>();
        if (unwrapped instanceof NativeArray) {
            NativeArray array = (NativeArray) unwrapped;
            int len = (int) array.getLength();
            for (int i = 0; i < len; i++) classes.add(asClass(array.get(i, array)));
        } else if (unwrapped instanceof Object[]) {
            for (Object item : (Object[]) unwrapped) classes.add(asClass(item));
        } else if (unwrapped instanceof List<?>) {
            for (Object item : (List<?>) unwrapped) classes.add(asClass(item));
        } else if (String.valueOf(unwrapped).trim().isEmpty()) {
            return new Class<?>[0];
        } else {
            classes.add(asClass(unwrapped));
        }
        return classes.toArray(new Class<?>[0]);
    }

    public Object[] toObjectArray(Object value) {
        Object unwrapped = unwrap(value);
        if (unwrapped == null || unwrapped == Scriptable.NOT_FOUND || unwrapped == Undefined.instance) return new Object[0];
        if (unwrapped instanceof NativeArray) {
            NativeArray array = (NativeArray) unwrapped;
            Object[] result = new Object[(int) array.getLength()];
            for (int i = 0; i < result.length; i++) result[i] = array.get(i, array);
            return result;
        }
        if (unwrapped instanceof List<?>) return ((List<?>) unwrapped).toArray(new Object[0]);
        if (unwrapped.getClass().isArray()) {
            int len = Array.getLength(unwrapped);
            Object[] result = new Object[len];
            for (int i = 0; i < len; i++) result[i] = Array.get(unwrapped, i);
            return result;
        }
        return new Object[]{unwrapped};
    }

    public Object callStatic(Object clazzOrName, String methodName, Object[] args, Object parameterTypes) throws Exception {
        Class<?> clazz = asClass(clazzOrName);
        Method method = hasExplicitTypes(parameterTypes)
                ? clazz.getDeclaredMethod(methodName, toClassArray(parameterTypes))
                : findBestMethod(clazz, methodName, args, true);
        method.setAccessible(true);
        Object result;
        try {
            result = method.invoke(null, convertArguments(method.getParameterTypes(), method.isVarArgs(), args));
        } catch (InvocationTargetException e) {
            throw rethrowInvocationTarget(e);
        }
        return method.getReturnType() == Void.TYPE ? Undefined.instance : wrapReturn(result);
    }

    public Object call(Object target, String methodName, Object[] args, Object parameterTypes) throws Exception {
        Object unwrappedTarget = unwrap(target);
        if (unwrappedTarget == null || unwrappedTarget == Undefined.instance || unwrappedTarget == Scriptable.NOT_FOUND) {
            throw new NullPointerException("Java.call target is null");
        }
        if (unwrappedTarget instanceof Class<?>) return callStatic(unwrappedTarget, methodName, args, parameterTypes);
        return callInstance(unwrappedTarget, methodName, args, parameterTypes);
    }

    /**
     * Invoke a method on the object itself, even when the object is java.lang.Class.
     * JavaClassWrapper uses this to expose Class meta-methods such as
     * getDeclaredMethod(String, Class<?>...) without treating the wrapped class as
     * a request for a static method on that application class.
     */
    public Object callInstance(Object target, String methodName, Object[] args, Object parameterTypes) throws Exception {
        Object unwrappedTarget = unwrap(target);
        if (unwrappedTarget == null || unwrappedTarget == Undefined.instance || unwrappedTarget == Scriptable.NOT_FOUND) {
            throw new NullPointerException("Java instance call target is null");
        }
        if (unwrappedTarget instanceof Method && "invoke".equals(methodName) && !hasExplicitTypes(parameterTypes)) {
            return invokeReflectedMethod((Method) unwrappedTarget, args == null ? new Object[0] : args);
        }
        Method method = hasExplicitTypes(parameterTypes)
                ? unwrappedTarget.getClass().getDeclaredMethod(methodName, toClassArray(parameterTypes))
                : findBestMethod(unwrappedTarget.getClass(), methodName, args, false);
        method.setAccessible(true);
        Object result;
        try {
            result = method.invoke(unwrappedTarget, convertArguments(method.getParameterTypes(), method.isVarArgs(), args));
        } catch (InvocationTargetException e) {
            throw rethrowInvocationTarget(e);
        }
        return method.getReturnType() == Void.TYPE ? Undefined.instance : wrapReturn(result);
    }

    private Object invokeReflectedMethod(Method targetMethod, Object[] invokeArgs) throws Exception {
        targetMethod.setAccessible(true);
        Object receiver = invokeArgs.length == 0 ? null : unwrap(invokeArgs[0]);
        Object[] actualArgs = new Object[Math.max(0, invokeArgs.length - 1)];
        if (invokeArgs.length > 1) System.arraycopy(invokeArgs, 1, actualArgs, 0, actualArgs.length);

        Class<?>[] realTypes = targetMethod.getParameterTypes();
        if (actualArgs.length == 1 && realTypes.length != 1 && isArrayLikeValue(unwrap(actualArgs[0]))) {
            actualArgs = toObjectArray(actualArgs[0]);
        }

        Object result;
        try {
            result = targetMethod.invoke(receiver, convertArguments(realTypes, targetMethod.isVarArgs(), actualArgs));
        } catch (InvocationTargetException e) {
            throw rethrowInvocationTarget(e);
        }
        return targetMethod.getReturnType() == Void.TYPE ? Undefined.instance : wrapReturn(result);
    }

    public Object newInstance(Object clazzOrName, Object[] args, Object parameterTypes) throws Exception {
        Class<?> clazz = asClass(clazzOrName);
        Constructor<?> constructor = hasExplicitTypes(parameterTypes)
                ? clazz.getDeclaredConstructor(toClassArray(parameterTypes))
                : findBestConstructor(clazz, args);
        constructor.setAccessible(true);
        Object result;
        try {
            result = constructor.newInstance(convertArguments(constructor.getParameterTypes(), constructor.isVarArgs(), args));
        } catch (InvocationTargetException e) {
            throw rethrowInvocationTarget(e);
        }
        return wrapConstructedObject(result);
    }

    public Object getStatic(Object clazzOrName, String fieldName) throws Exception {
        Class<?> clazz = asClass(clazzOrName);
        Field field = findField(clazz, fieldName);
        if (!Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException(clazz.getName() + "." + fieldName + " is not static");
        field.setAccessible(true);
        return wrapReturn(field.get(null));
    }

    public void setStatic(Object clazzOrName, String fieldName, Object value) throws Exception {
        Class<?> clazz = asClass(clazzOrName);
        Field field = findField(clazz, fieldName);
        if (!Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException(clazz.getName() + "." + fieldName + " is not static");
        field.setAccessible(true);
        field.set(null, convertArgument(field.getType(), value));
    }

    public Object get(Object target, String fieldName) throws Exception {
        Object unwrappedTarget = unwrap(target);
        if (unwrappedTarget instanceof Class<?>) return getStatic(unwrappedTarget, fieldName);
        if (unwrappedTarget == null) throw new NullPointerException("Java.get target is null");
        Field field = findField(unwrappedTarget.getClass(), fieldName);
        field.setAccessible(true);
        return wrapReturn(field.get(unwrappedTarget));
    }

    public void set(Object target, String fieldName, Object value) throws Exception {
        Object unwrappedTarget = unwrap(target);
        if (unwrappedTarget instanceof Class<?>) {
            setStatic(unwrappedTarget, fieldName, value);
            return;
        }
        if (unwrappedTarget == null) throw new NullPointerException("Java.set target is null");
        Field field = findField(unwrappedTarget.getClass(), fieldName);
        field.setAccessible(true);
        field.set(unwrappedTarget, convertArgument(field.getType(), value));
    }

    public JavaClassWrapper wrapClass(Class<?> clazz) {
        return new JavaClassWrapper(bridge, clazz);
    }

    public Object wrapReturn(Object value) {
        Object v = unwrap(value);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return null;
        if (v instanceof Scriptable) return v;
        if (v instanceof Class<?>) return wrapClass((Class<?>) v);
        if (isJsPrimitiveLikeReturn(v)) return v;
        if (v instanceof Map<?, ?> || v instanceof Iterable<?> || v.getClass().isArray()) return deepWrapToJs(v);
        if (v instanceof Throwable || v instanceof java.io.File) return JsApiValueNormalizer.toJs(v);
        return new JavaObjectWrapper(bridge, v);
    }

    /**
     * Constructors should always produce a Java object wrapper.  Returning raw
     * String/Number-like values here breaks Rhino's `new JavaType(...)` path,
     * and Number subclasses such as AtomicInteger are still ordinary Java
     * objects with instance methods.
     */
    public Object wrapConstructedObject(Object value) {
        Object v = unwrap(value);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return null;
        if (v instanceof Scriptable) return v;
        if (v instanceof Class<?>) return wrapClass((Class<?>) v);
        return new JavaObjectWrapper(bridge, v);
    }

    public Object wrapTypedValue(Object value, Class<?> explicitType) {
        Object v = unwrap(value);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return null;
        if (v instanceof JavaObjectWrapper) return new JavaObjectWrapper(bridge, ((JavaObjectWrapper) v).getRawObject(), explicitType);
        if (v instanceof Class<?>) return wrapClass((Class<?>) v);
        return new JavaObjectWrapper(bridge, v, explicitType);
    }

    private boolean isJsPrimitiveLikeReturn(Object value) {
        if (value instanceof String || value instanceof Boolean || value instanceof Character) return true;
        return isBoxedPrimitiveNumberClass(value.getClass());
    }

    private boolean isBoxedPrimitiveNumberClass(Class<?> clazz) {
        return clazz == Byte.class || clazz == Short.class || clazz == Integer.class || clazz == Long.class
                || clazz == Float.class || clazz == Double.class;
    }

    private Object deepWrapToJs(Object value) {
        Object v = unwrap(value);
        if (v == null) return null;
        if (v instanceof Map<?, ?>) {
            org.mozilla.javascript.NativeObject out = new org.mozilla.javascript.NativeObject();
            attachScope(out);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) v).entrySet()) {
                if (entry.getKey() == null) continue;
                out.put(String.valueOf(entry.getKey()), out, wrapReturn(entry.getValue()));
            }
            return out;
        }
        if (v instanceof Iterable<?>) {
            ArrayList<Object> out = new ArrayList<>();
            for (Object item : (Iterable<?>) v) out.add(wrapReturn(item));
            return newNativeArray(out.toArray(new Object[0]));
        }
        if (v.getClass().isArray()) {
            int len = Array.getLength(v);
            Object[] out = new Object[len];
            for (int i = 0; i < len; i++) out[i] = wrapReturn(Array.get(v, i));
            return newNativeArray(out);
        }
        return JsApiValueNormalizer.toJs(v);
    }

    private org.mozilla.javascript.NativeArray newNativeArray(Object[] values) {
        org.mozilla.javascript.NativeArray array = new org.mozilla.javascript.NativeArray(values);
        attachScope(array);
        return array;
    }

    private void attachScope(Scriptable scriptable) {
        Scriptable scope = bridge.getScope();
        if (scope != null) {
            scriptable.setParentScope(scope);
            if (scriptable instanceof ScriptableObject) {
                Object proto = ScriptableObject.getObjectPrototype(scope);
                if (proto instanceof Scriptable) ((ScriptableObject) scriptable).setPrototype((Scriptable) proto);
            }
        }
    }

    public boolean hasMethod(Class<?> clazz, String methodName, boolean requireStatic) {
        try {
            findAnyMethod(clazz, methodName, -1, requireStatic);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public Method findAnyMethod(Class<?> clazz, String methodName, int argCount, boolean requireStatic) throws NoSuchMethodException {
        for (Method method : allMethods(clazz)) {
            if (!method.getName().equals(methodName)) continue;
            if (argCount >= 0 && method.getParameterTypes().length != argCount) continue;
            if (requireStatic != Modifier.isStatic(method.getModifiers())) continue;
            method.setAccessible(true);
            return method;
        }
        throw new NoSuchMethodException(clazz.getName() + "." + methodName + "/" + argCount + (requireStatic ? " static" : ""));
    }

    public Constructor<?> findAnyConstructor(Class<?> clazz, int argCount) throws NoSuchMethodException {
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (argCount >= 0 && constructor.getParameterTypes().length != argCount) continue;
            constructor.setAccessible(true);
            return constructor;
        }
        throw new NoSuchMethodException(clazz.getName() + ".<init>/" + argCount);
    }

    public Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            Field field = clazz.getField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException ignored) {
        }
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(clazz.getName() + "." + fieldName);
    }

    private Method findBestMethod(Class<?> clazz, String methodName, Object[] args, boolean requireStatic) throws NoSuchMethodException {
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Method method : allMethods(clazz)) {
            if (!method.getName().equals(methodName)) continue;
            if (!acceptsArgumentCount(method.getParameterTypes(), method.isVarArgs(), args.length)) continue;
            if (requireStatic != Modifier.isStatic(method.getModifiers())) continue;
            int score = scoreArguments(method.getParameterTypes(), method.isVarArgs(), args);
            if (score < 0) continue;
            if (score < bestScore) {
                bestScore = score;
                best = method;
            }
        }
        if (best != null) {
            best.setAccessible(true);
            return best;
        }
        throw new NoSuchMethodException(clazz.getName() + "." + methodName + "/" + args.length + (requireStatic ? " static" : ""));
    }

    private Constructor<?> findBestConstructor(Class<?> clazz, Object[] args) throws NoSuchMethodException {
        Constructor<?> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (!acceptsArgumentCount(constructor.getParameterTypes(), constructor.isVarArgs(), args.length)) continue;
            int score = scoreArguments(constructor.getParameterTypes(), constructor.isVarArgs(), args);
            if (score < 0) continue;
            if (score < bestScore) {
                bestScore = score;
                best = constructor;
            }
        }
        if (best != null) {
            best.setAccessible(true);
            return best;
        }
        throw new NoSuchMethodException(clazz.getName() + ".<init>/" + args.length);
    }

    private List<Method> allMethods(Class<?> clazz) {
        LinkedHashMap<String, Method> out = new LinkedHashMap<>();
        for (Method method : clazz.getMethods()) out.putIfAbsent(signature(method), method);
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) out.putIfAbsent(signature(method), method);
        }
        return new ArrayList<>(out.values());
    }

    private String signature(Method method) {
        StringBuilder sb = new StringBuilder(method.getName()).append('(');
        for (Class<?> type : method.getParameterTypes()) sb.append(type.getName()).append(';');
        return sb.append(')').toString();
    }

    private boolean hasExplicitTypes(Object value) {
        Object unwrapped = unwrap(value);
        return unwrapped != null && unwrapped != Undefined.instance && unwrapped != Scriptable.NOT_FOUND;
    }

    private boolean acceptsArgumentCount(Class<?>[] targetTypes, boolean varArgs, int argCount) {
        if (!varArgs) return targetTypes.length == argCount;
        return argCount >= targetTypes.length - 1;
    }

    private int scoreArguments(Class<?>[] targetTypes, boolean varArgs, Object[] args) {
        if (!acceptsArgumentCount(targetTypes, varArgs, args.length)) return -1;
        if (!varArgs) {
            int score = 0;
            for (int i = 0; i < targetTypes.length; i++) {
                int item = scoreArgument(targetTypes[i], args[i]);
                if (item < 0) return -1;
                score += item;
            }
            return score;
        }

        int fixedCount = targetTypes.length - 1;
        int score = 0;
        for (int i = 0; i < fixedCount; i++) {
            int item = scoreArgument(targetTypes[i], args[i]);
            if (item < 0) return -1;
            score += item;
        }

        Class<?> arrayType = targetTypes[targetTypes.length - 1];
        Class<?> componentType = arrayType.getComponentType();
        if (componentType == null) return -1;

        // Prefer an already array-like final argument when callers pass the Java
        // varargs array explicitly, but also accept ordinary trailing arguments.
        if (args.length == targetTypes.length && isArrayLikeValue(unwrap(args[fixedCount]))) {
            int item = scoreArgument(arrayType, args[fixedCount]);
            return item < 0 ? -1 : score + item;
        }

        for (int i = fixedCount; i < args.length; i++) {
            int item = scoreArgument(componentType, args[i]);
            if (item < 0) return -1;
            score += item + 1;
        }
        return score + 4;
    }

    private int scoreArgument(Class<?> targetType, Object arg) {
        boolean directJavaObject = isDirectJavaObject(arg);
        Object value = unwrap(arg);
        if (value == Undefined.instance || value == Scriptable.NOT_FOUND) value = null;
        if (value == null) return targetType.isPrimitive() ? -1 : 1;
        Class<?> boxed = boxType(targetType);

        // Java wrappers / NativeJavaObject values are already Java values.  Do
        // not treat them as JS primitives and do not perform numeric/string
        // auto-conversion for overload resolution.  They may still match their
        // own type, a super type, Object, or the boxed form of a primitive.
        if (directJavaObject) {
            if (targetType.isArray()) return targetType.isInstance(value) ? 0 : -1;
            if (boxed.isInstance(value)) return 0;
            if (!targetType.isPrimitive() && targetType.isAssignableFrom(value.getClass())) return 1;
            if (targetType == Object.class) return 10;
            return -1;
        }

        if (targetType.isInterface() && bridge.getProxyFactory().canAutoProxy(targetType, arg)) return value instanceof Function ? 1 : 3;
        if (Number.class.isAssignableFrom(boxed) && value instanceof Number) return scoreNumberArgument(boxed, (Number) value);
        if (boxed.isInstance(value)) return 0;
        if (boxed == Boolean.class && value instanceof Boolean) return 0;
        if (targetType == Class.class && value instanceof CharSequence && canLoadClassName(String.valueOf(value))) return 2;
        if (boxed == Character.class && (value instanceof Character || String.valueOf(value).length() == 1)) return 2;
        if (targetType == String.class || targetType == CharSequence.class) return 3;
        if (targetType.isArray() && isArrayLike(value)) return 4;
        if (targetType == Object.class) return 10;
        return boxed.isAssignableFrom(value.getClass()) ? 1 : -1;
    }

    private int scoreNumberArgument(Class<?> boxedTarget, Number value) {
        if (!isBoxedPrimitiveNumberClass(boxedTarget)) {
            return boxedTarget.isInstance(value) ? 0 : 2;
        }

        boolean integral = isIntegralNumber(value);
        if (boxedTarget == Integer.class) return integral && fitsLongRange(value, Integer.MIN_VALUE, Integer.MAX_VALUE) ? 0 : 6;
        if (boxedTarget == Long.class) return integral ? 1 : 6;
        if (boxedTarget == Short.class) return integral && fitsLongRange(value, Short.MIN_VALUE, Short.MAX_VALUE) ? 2 : 7;
        if (boxedTarget == Byte.class) return integral && fitsLongRange(value, Byte.MIN_VALUE, Byte.MAX_VALUE) ? 3 : 8;
        if (boxedTarget == Float.class) return integral ? 5 : 1;
        if (boxedTarget == Double.class) return integral ? 5 : 0;
        return 6;
    }

    private boolean isIntegralNumber(Number value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return true;
        double d = value.doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d) && Math.rint(d) == d;
    }

    private boolean fitsLongRange(Number value, long min, long max) {
        double d = value.doubleValue();
        return d >= min && d <= max;
    }

    private boolean canLoadClassName(String className) {
        if (className == null || className.trim().isEmpty()) return false;
        try {
            loadClass(className.trim());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public Object[] convertArguments(Class<?>[] targetTypes, Object[] args) {
        return convertArguments(targetTypes, false, args);
    }

    public Object[] convertArguments(Class<?>[] targetTypes, boolean varArgs, Object[] args) {
        if (!varArgs) {
            Object[] out = new Object[args.length];
            for (int i = 0; i < args.length; i++) out[i] = convertArgument(targetTypes[i], args[i]);
            return out;
        }

        int fixedCount = targetTypes.length - 1;
        Object[] out = new Object[targetTypes.length];
        for (int i = 0; i < fixedCount; i++) out[i] = convertArgument(targetTypes[i], args[i]);

        Class<?> arrayType = targetTypes[targetTypes.length - 1];
        Class<?> componentType = arrayType.getComponentType();
        if (args.length == targetTypes.length && isArrayLikeValue(unwrap(args[fixedCount]))) {
            out[fixedCount] = convertArgument(arrayType, args[fixedCount]);
        } else {
            int varArgCount = Math.max(0, args.length - fixedCount);
            Object array = Array.newInstance(componentType, varArgCount);
            for (int i = 0; i < varArgCount; i++) {
                Array.set(array, i, convertArgument(componentType, args[fixedCount + i]));
            }
            out[fixedCount] = array;
        }
        return out;
    }

    public Object convertArgument(Class<?> targetType, Object arg) {
        boolean directJavaObject = isDirectJavaObject(arg);
        Object value = unwrap(arg);
        if (value == Undefined.instance || value == Scriptable.NOT_FOUND) value = null;
        if (value == null) return null;
        if (targetType.isArray() && targetType.isInstance(value)) return value;

        // Values that are already Java wrappers / NativeJavaObject are not
        // converted again.  They are only unwrapped and handed back to Java.
        // This preserves explicit values created by Java.to(...), Java.type(...)
        // calls, loader.loadClass(...), Method/Field/ClassLoader instances, etc.
        if (directJavaObject) {
            Class<?> boxed = boxType(targetType);
            if (boxed.isInstance(value)) return value;
            if (!targetType.isPrimitive() && targetType.isInstance(value)) return value;
            if (targetType == Object.class) return value;
            return value;
        }

        if (targetType.isInterface()) {
            Object proxied = bridge.getProxyFactory().maybeCreateProxy(targetType, arg);
            if (proxied == null || targetType.isInstance(proxied)) return proxied;
        }
        if (targetType.isArray() && isArrayLike(value)) return convertArray(targetType.getComponentType(), value);
        Class<?> boxed = boxType(targetType);
        if (boxed.isInstance(value)) return value;
        if (boxed == Integer.class && value instanceof Number) return ((Number) value).intValue();
        if (boxed == Long.class && value instanceof Number) return ((Number) value).longValue();
        if (boxed == Float.class && value instanceof Number) return ((Number) value).floatValue();
        if (boxed == Double.class && value instanceof Number) return ((Number) value).doubleValue();
        if (boxed == Short.class && value instanceof Number) return ((Number) value).shortValue();
        if (boxed == Byte.class && value instanceof Number) return ((Number) value).byteValue();
        if (boxed == Boolean.class && value instanceof Boolean) return value;
        if (boxed == Character.class) {
            if (value instanceof Character) return value;
            String text = String.valueOf(value);
            return text.isEmpty() ? '\0' : text.charAt(0);
        }
        if (targetType == Class.class) {
            if (value instanceof Class<?>) return value;
            if (value instanceof CharSequence) {
                try {
                    return loadClass(String.valueOf(value).trim());
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("Cannot resolve Java class type: " + value, e);
                }
            }
        }
        if (targetType == String.class || targetType == CharSequence.class) return String.valueOf(value);
        return value;
    }

    private Object convertArray(Class<?> componentType, Object value) {
        Object[] items = toObjectArray(value);
        Object array = Array.newInstance(componentType, items.length);
        for (int i = 0; i < items.length; i++) Array.set(array, i, convertArgument(componentType, items[i]));
        return array;
    }

    private boolean isArrayLike(Object value) {
        return isArrayLikeValue(value);
    }

    private boolean isArrayLikeValue(Object value) {
        return value != null && (value instanceof NativeArray || value instanceof Object[] || value instanceof List<?> || value.getClass().isArray());
    }

    Object defaultValue(Class<?> type) {
        if (type == Void.TYPE) return null;
        if (!type.isPrimitive()) return null;
        if (type == Boolean.TYPE) return false;
        if (type == Character.TYPE) return '\0';
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0f;
        if (type == Double.TYPE) return 0d;
        return null;
    }

    private Exception rethrowInvocationTarget(InvocationTargetException e) throws Exception {
        Throwable cause = e.getTargetException();
        if (cause instanceof Exception) throw (Exception) cause;
        if (cause instanceof Error) throw (Error) cause;
        throw new RuntimeException(cause == null ? e : cause);
    }

    private Class<?> boxType(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == Boolean.TYPE) return Boolean.class;
        if (type == Byte.TYPE) return Byte.class;
        if (type == Character.TYPE) return Character.class;
        if (type == Short.TYPE) return Short.class;
        if (type == Integer.TYPE) return Integer.class;
        if (type == Long.TYPE) return Long.class;
        if (type == Float.TYPE) return Float.class;
        if (type == Double.TYPE) return Double.class;
        if (type == Void.TYPE) return Void.class;
        return type;
    }

    public static Object unwrap(Object value) {
        if (value == Context.getUndefinedValue() || value == Scriptable.NOT_FOUND || value == Undefined.instance) return null;
        if (value instanceof JavaClassWrapper) return ((JavaClassWrapper) value).getRawClass();
        if (value instanceof JavaObjectWrapper) return ((JavaObjectWrapper) value).getRawObject();
        if (value instanceof JavaListWrapper) return ((JavaListWrapper) value).getRawList();
        if (value instanceof JavaMapWrapper) return ((JavaMapWrapper) value).getRawMap();
        if (value instanceof Wrapper) return ((Wrapper) value).unwrap();
        return value;
    }

    private boolean isDirectJavaObject(Object value) {
        if (value == null || value == Context.getUndefinedValue() || value == Scriptable.NOT_FOUND || value == Undefined.instance) return false;
        if (value instanceof JavaClassWrapper || value instanceof JavaObjectWrapper || value instanceof JavaListWrapper || value instanceof JavaMapWrapper) return true;
        if (value instanceof Wrapper) {
            Object raw = ((Wrapper) value).unwrap();
            return raw != null && !(raw instanceof Scriptable);
        }
        return false;
    }
}
