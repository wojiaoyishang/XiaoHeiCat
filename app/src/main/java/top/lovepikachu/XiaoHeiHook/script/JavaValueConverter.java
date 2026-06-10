package top.lovepikachu.XiaoHeiHook.script;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit JS -> Java conversion backend used by Java.to(...). */
public final class JavaValueConverter {
    private JavaValueConverter() { }

    public static Class<?> resolveType(JavaBridge bridge, Object typeValue) throws ClassNotFoundException {
        Object raw = unwrapJavaLike(typeValue);
        if (raw instanceof Class<?>) return (Class<?>) raw;
        if (raw instanceof JavaClassWrapper) return ((JavaClassWrapper) raw).getRawClass();
        if (raw == null || raw == Undefined.instance || raw == Scriptable.NOT_FOUND) {
            throw new ClassNotFoundException("null");
        }
        String typeName = String.valueOf(raw).trim();
        if (typeName.isEmpty()) throw new ClassNotFoundException(typeName);
        return resolveTypeName(bridge, typeName);
    }

    public static Object explicitToJava(JavaBridge bridge, Class<?> targetType, Object value, JavaToOptions options) throws Exception {
        if (options == null) options = JavaToOptions.DEFAULT;
        if (targetType == null) throw new NullPointerException("targetType");

        boolean directJavaObject = isWrappedJavaValue(value);
        Object raw = unwrapJavaLike(value);
        if (raw == Undefined.instance || raw == Scriptable.NOT_FOUND || raw == Context.getUndefinedValue()) raw = null;
        if (raw == null) {
            if (options.hasDefaultValue) {
                return explicitToJava(bridge, targetType, options.defaultValue, options.withoutDefaultValue());
            }
            if (options.nullAsEmpty && (targetType == String.class || targetType == CharSequence.class)) return "";
            if (targetType.isPrimitive()) throw new IllegalArgumentException("null cannot be converted to primitive " + targetType.getName());
            return null;
        }

        if (targetType == Void.TYPE || targetType == Void.class) return null;

        Class<?> boxedTarget = boxType(targetType);
        if (directJavaObject) {
            if (targetType == Object.class) return raw;
            if (targetType == Class.class && raw instanceof Class<?>) return raw;
            if (boxedTarget.isInstance(raw)) return raw;
            if (!targetType.isPrimitive() && targetType.isInstance(raw)) return raw;
        }

        if (targetType == Object.class) return toPlainJavaObject(bridge, value, options);
        if (targetType == Class.class) return toClassValue(bridge, value);

        if (boxedTarget.isInstance(raw)) return raw;
        if (!targetType.isPrimitive() && targetType.isInstance(raw)) return raw;

        if (targetType.isArray()) return toJavaArray(bridge, targetType.getComponentType(), value, options);
        if (targetType.isEnum()) return toEnumValue(targetType, raw);

        if (boxedTarget == String.class || targetType == CharSequence.class) return String.valueOf(raw);
        if (boxedTarget == Boolean.class) return toBoolean(raw, options);
        if (boxedTarget == Character.class) return toCharacter(raw, options);
        if (boxedTarget == Byte.class) return toByte(raw, options);
        if (boxedTarget == Short.class) return toShort(raw);
        if (boxedTarget == Integer.class) return toInteger(raw, options);
        if (boxedTarget == Long.class) return toLong(raw, options);
        if (boxedTarget == Float.class) return toFloat(raw, options);
        if (boxedTarget == Double.class) return toDouble(raw, options);
        if (boxedTarget == BigInteger.class) return toBigInteger(raw);
        if (boxedTarget == BigDecimal.class) return toBigDecimal(raw);

        if (File.class.isAssignableFrom(targetType)) return new File(String.valueOf(raw));
        if ("android.net.Uri".equals(targetType.getName())) return parseAndroidUri(targetType, raw);

        if (List.class.isAssignableFrom(targetType)) return toJavaList(bridge, targetType, value, options);
        if (Set.class.isAssignableFrom(targetType)) return toJavaSet(bridge, targetType, value, options);
        if (Map.class.isAssignableFrom(targetType)) return toJavaMap(bridge, targetType, value, options);

        return toCustomJavaObject(bridge, targetType, value, options);
    }

    public static boolean isJavaLikeValue(Object value) {
        if (value == null || value == Undefined.instance || value == Scriptable.NOT_FOUND || value == Context.getUndefinedValue()) return false;
        if (value instanceof JavaClassWrapper || value instanceof JavaObjectWrapper || value instanceof JavaListWrapper || value instanceof JavaMapWrapper) return true;
        if (value instanceof Wrapper) {
            Object raw = ((Wrapper) value).unwrap();
            return raw != null && !(raw instanceof Scriptable);
        }
        return !(value instanceof Scriptable);
    }

    public static Object unwrapJavaLike(Object value) {
        if (value == Context.getUndefinedValue() || value == Scriptable.NOT_FOUND || value == Undefined.instance) return null;
        if (value instanceof JavaClassWrapper) return ((JavaClassWrapper) value).getRawClass();
        if (value instanceof JavaObjectWrapper) return ((JavaObjectWrapper) value).getRawObject();
        if (value instanceof JavaListWrapper) return ((JavaListWrapper) value).getRawList();
        if (value instanceof JavaMapWrapper) return ((JavaMapWrapper) value).getRawMap();
        if (value instanceof Wrapper) return ((Wrapper) value).unwrap();
        return value;
    }

    private static boolean isWrappedJavaValue(Object value) {
        if (value == null || value == Context.getUndefinedValue() || value == Scriptable.NOT_FOUND || value == Undefined.instance) return false;
        if (value instanceof JavaClassWrapper || value instanceof JavaObjectWrapper || value instanceof JavaListWrapper || value instanceof JavaMapWrapper) return true;
        if (value instanceof Wrapper) {
            Object raw = ((Wrapper) value).unwrap();
            return raw != null && !(raw instanceof Scriptable);
        }
        return false;
    }

    public static boolean isJsArrayLike(Object value) {
        Object raw = unwrapJavaLike(value);
        return raw != null && (raw instanceof NativeArray || raw instanceof Object[] || raw instanceof List<?> || raw.getClass().isArray());
    }

    public static boolean isJsPlainObject(Object value) {
        Object raw = unwrapJavaLike(value);
        return raw instanceof Scriptable && !(raw instanceof NativeArray) && !(raw instanceof Function) && !(raw instanceof JavaClassWrapper)
                && !(raw instanceof JavaObjectWrapper) && !(raw instanceof JavaListWrapper) && !(raw instanceof JavaMapWrapper);
    }

    private static Class<?> resolveTypeName(JavaBridge bridge, String typeName) throws ClassNotFoundException {
        if (typeName.endsWith("[]")) {
            Class<?> component = resolveTypeName(bridge, typeName.substring(0, typeName.length() - 2));
            return Array.newInstance(component, 0).getClass();
        }
        return bridge.getInvoker().loadClass(typeName);
    }

    private static Object toClassValue(JavaBridge bridge, Object value) throws ClassNotFoundException {
        Object raw = unwrapJavaLike(value);
        if (raw instanceof Class<?>) return raw;
        if (raw instanceof JavaClassWrapper) return ((JavaClassWrapper) raw).getRawClass();
        return resolveType(bridge, raw);
    }

    private static Object toPlainJavaObject(JavaBridge bridge, Object value, JavaToOptions options) throws Exception {
        Object raw = unwrapJavaLike(value);
        if (raw == null || raw == Undefined.instance || raw == Scriptable.NOT_FOUND || raw == Context.getUndefinedValue()) return null;
        if (isWrappedJavaValue(value)) return raw;
        if (!options.deep) return raw;
        if (raw instanceof NativeArray || raw instanceof List<?> || raw.getClass().isArray()) return toJavaList(bridge, ArrayList.class, value, options);
        if (raw instanceof Map<?, ?> || isJsPlainObject(value)) return toJavaMap(bridge, HashMap.class, value, options);
        return raw;
    }

    private static Object toJavaArray(JavaBridge bridge, Class<?> componentType, Object value, JavaToOptions options) throws Exception {
        Object raw = unwrapJavaLike(value);
        if (raw == null) return null;

        if (componentType == Byte.TYPE && raw instanceof String) {
            return bytesFromString((String) raw, options);
        }
        if (componentType == Character.TYPE && raw instanceof String && !looksLikeArrayString((String) raw)) {
            String text = (String) raw;
            Object array = Array.newInstance(componentType, text.length());
            for (int i = 0; i < text.length(); i++) Array.set(array, i, text.charAt(i));
            return array;
        }

        Object[] items = toObjectArray(raw);
        Object array = Array.newInstance(componentType, items.length);
        JavaToOptions elementOptions = options.forElement();
        for (int i = 0; i < items.length; i++) {
            Array.set(array, i, explicitToJava(bridge, componentType, items[i], elementOptions));
        }
        return array;
    }

    private static Object[] toObjectArray(Object value) {
        Object raw = unwrapJavaLike(value);
        if (raw == null || raw == Undefined.instance || raw == Scriptable.NOT_FOUND || raw == Context.getUndefinedValue()) return new Object[0];
        if (raw instanceof NativeArray) {
            NativeArray array = (NativeArray) raw;
            Object[] out = new Object[(int) array.getLength()];
            for (int i = 0; i < out.length; i++) out[i] = array.get(i, array);
            return out;
        }
        if (raw instanceof List<?>) return ((List<?>) raw).toArray(new Object[0]);
        if (raw.getClass().isArray()) {
            int len = Array.getLength(raw);
            Object[] out = new Object[len];
            for (int i = 0; i < len; i++) out[i] = Array.get(raw, i);
            return out;
        }
        return new Object[]{raw};
    }

    private static byte[] bytesFromString(String value, JavaToOptions options) {
        String encoding = options.encoding == null ? "utf-8" : options.encoding.trim().toLowerCase();
        switch (encoding) {
            case "base64":
                return Base64.getDecoder().decode(value);
            case "utf8":
            case "utf-8":
                return value.getBytes(StandardCharsets.UTF_8);
            default:
                return value.getBytes(Charset.forName(encoding));
        }
    }

    private static boolean looksLikeArrayString(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("[") && trimmed.endsWith("]");
    }

    private static Object toJavaList(JavaBridge bridge, Class<?> targetType, Object value, JavaToOptions options) throws Exception {
        List<Object> out = instantiateList(targetType);
        Class<?> elementType = options.elementType;
        for (Object item : toObjectArray(value)) {
            out.add(elementType == null ? toPlainJavaObject(bridge, item, options) : explicitToJava(bridge, elementType, item, options.forElement()));
        }
        return out;
    }

    private static Object toJavaSet(JavaBridge bridge, Class<?> targetType, Object value, JavaToOptions options) throws Exception {
        Set<Object> out = instantiateSet(targetType);
        Class<?> elementType = options.elementType;
        for (Object item : toObjectArray(value)) {
            out.add(elementType == null ? toPlainJavaObject(bridge, item, options) : explicitToJava(bridge, elementType, item, options.forElement()));
        }
        return out;
    }

    private static Object toJavaMap(JavaBridge bridge, Class<?> targetType, Object value, JavaToOptions options) throws Exception {
        Map<Object, Object> out = instantiateMap(targetType);
        Class<?> keyType = options.keyType == null ? String.class : options.keyType;
        Class<?> valueType = options.valueType;
        Object raw = unwrapJavaLike(value);
        if (raw instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                Object k = explicitToJava(bridge, keyType, entry.getKey(), options.forElement());
                Object v = valueType == null ? toPlainJavaObject(bridge, entry.getValue(), options) : explicitToJava(bridge, valueType, entry.getValue(), options.forElement());
                out.put(k, v);
            }
            return out;
        }
        if (raw instanceof Scriptable) {
            Scriptable scriptable = (Scriptable) raw;
            for (Object id : scriptable.getIds()) {
                Object key = id instanceof Number ? ((Number) id).intValue() : String.valueOf(id);
                Object item = id instanceof Number
                        ? scriptable.get(((Number) id).intValue(), scriptable)
                        : ScriptableObject.getProperty(scriptable, String.valueOf(id));
                Object k = explicitToJava(bridge, keyType, key, options.forElement());
                Object v = valueType == null ? toPlainJavaObject(bridge, item, options) : explicitToJava(bridge, valueType, item, options.forElement());
                out.put(k, v);
            }
            return out;
        }
        throw new IllegalArgumentException("Cannot convert " + raw.getClass().getName() + " to Map");
    }

    @SuppressWarnings("unchecked")
    private static Object toEnumValue(Class<?> enumType, Object value) {
        if (enumType.isInstance(value)) return value;
        return Enum.valueOf((Class<Enum>) enumType.asSubclass(Enum.class), String.valueOf(value));
    }

    private static Object parseAndroidUri(Class<?> uriType, Object value) throws Exception {
        Method parse = uriType.getMethod("parse", String.class);
        return parse.invoke(null, String.valueOf(value));
    }

    private static Object toCustomJavaObject(JavaBridge bridge, Class<?> targetType, Object value, JavaToOptions options) throws Exception {
        Object raw = unwrapJavaLike(value);
        if (targetType.isInstance(raw)) return raw;

        Method valueOf = findValueOf(targetType, value, options, bridge);
        if (valueOf != null) {
            Object arg = explicitToJava(bridge, valueOf.getParameterTypes()[0], value, options.forElement());
            return valueOf.invoke(null, arg);
        }

        if (raw instanceof String) {
            Constructor<?> stringCtor = findConstructor(targetType, String.class);
            if (stringCtor != null) return stringCtor.newInstance(String.valueOf(raw));
        }

        for (Constructor<?> constructor : targetType.getDeclaredConstructors()) {
            if (constructor.getParameterTypes().length != 1) continue;
            if (constructor.getParameterTypes()[0] == targetType) continue;
            constructor.setAccessible(true);
            Object arg = explicitToJava(bridge, constructor.getParameterTypes()[0], value, options.forElement());
            return constructor.newInstance(arg);
        }
        throw new IllegalArgumentException("Cannot convert value to " + targetType.getName());
    }

    private static Method findValueOf(Class<?> targetType, Object value, JavaToOptions options, JavaBridge bridge) {
        List<Method> methods = new ArrayList<>();
        for (Method method : targetType.getMethods()) methods.add(method);
        for (Method method : targetType.getDeclaredMethods()) methods.add(method);
        for (Method method : methods) {
            if (!"valueOf".equals(method.getName())) continue;
            if (!Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterTypes().length != 1) continue;
            if (method.getParameterTypes()[0] == targetType) continue;
            if (!targetType.isAssignableFrom(method.getReturnType())) continue;
            try {
                method.setAccessible(true);
                explicitToJava(bridge, method.getParameterTypes()[0], value, options.forElement());
                return method;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Constructor<?> findConstructor(Class<?> targetType, Class<?> paramType) {
        try {
            Constructor<?> constructor = targetType.getDeclaredConstructor(paramType);
            constructor.setAccessible(true);
            return constructor;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> instantiateList(Class<?> targetType) throws Exception {
        if (targetType == null || targetType == List.class || targetType == java.util.Collection.class || targetType == Iterable.class) return new ArrayList<>();
        if (targetType.isInterface() || Modifier.isAbstract(targetType.getModifiers())) return new ArrayList<>();
        return (List<Object>) targetType.getDeclaredConstructor().newInstance();
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> instantiateSet(Class<?> targetType) throws Exception {
        if (targetType == null || targetType == Set.class || targetType == java.util.Collection.class || targetType == Iterable.class) return new LinkedHashSet<>();
        if (targetType.isInterface() || Modifier.isAbstract(targetType.getModifiers())) return new LinkedHashSet<>();
        return (Set<Object>) targetType.getDeclaredConstructor().newInstance();
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> instantiateMap(Class<?> targetType) throws Exception {
        if (targetType == null || targetType == Map.class) return new LinkedHashMap<>();
        if (targetType.isInterface() || Modifier.isAbstract(targetType.getModifiers())) return new LinkedHashMap<>();
        return (Map<Object, Object>) targetType.getDeclaredConstructor().newInstance();
    }

    private static Boolean toBoolean(Object value, JavaToOptions options) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number && options.numberAsBoolean) return ((Number) value).doubleValue() != 0d;
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text)) return false;
        throw new IllegalArgumentException("Cannot convert to boolean: " + value);
    }

    private static Character toCharacter(Object value, JavaToOptions options) {
        if (value instanceof Character) return (Character) value;
        String text = String.valueOf(value);
        if (text.isEmpty()) return '\0';
        if (text.length() > 1 && !options.firstChar) {
            throw new IllegalArgumentException("Cannot convert multi-character string to char without options.firstChar=true");
        }
        return text.charAt(0);
    }

    private static Byte toByte(Object value, JavaToOptions options) {
        int intValue = toInteger(value);
        if (options.unsigned) {
            if (intValue < 0 || intValue > 255) throw new IllegalArgumentException("Unsigned byte out of range: " + intValue);
            return (byte) intValue;
        }
        if (intValue < Byte.MIN_VALUE || intValue > Byte.MAX_VALUE) throw new IllegalArgumentException("byte out of range: " + intValue);
        return (byte) intValue;
    }

    private static Short toShort(Object value) {
        if (value instanceof Number) return ((Number) value).shortValue();
        return Short.valueOf(String.valueOf(value).trim());
    }

    private static Integer toInteger(Object value) {
        return toInteger(value, JavaToOptions.DEFAULT);
    }

    private static Integer toInteger(Object value, JavaToOptions options) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof Boolean) {
            if (options.booleanAsNumber) return ((Boolean) value) ? 1 : 0;
            throw new IllegalArgumentException("boolean cannot be converted to int without options.booleanAsNumber=true");
        }
        return Integer.valueOf(String.valueOf(value).trim());
    }

    private static Long toLong(Object value) {
        return toLong(value, JavaToOptions.DEFAULT);
    }

    private static Long toLong(Object value, JavaToOptions options) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof Boolean) {
            if (options.booleanAsNumber) return ((Boolean) value) ? 1L : 0L;
            throw new IllegalArgumentException("boolean cannot be converted to long without options.booleanAsNumber=true");
        }
        return Long.valueOf(String.valueOf(value).trim());
    }

    private static Float toFloat(Object value) {
        return toFloat(value, JavaToOptions.DEFAULT);
    }

    private static Float toFloat(Object value, JavaToOptions options) {
        if (value instanceof Number) return ((Number) value).floatValue();
        if (value instanceof Boolean) {
            if (options.booleanAsNumber) return ((Boolean) value) ? 1f : 0f;
            throw new IllegalArgumentException("boolean cannot be converted to float without options.booleanAsNumber=true");
        }
        return Float.valueOf(String.valueOf(value).trim());
    }

    private static Double toDouble(Object value) {
        return toDouble(value, JavaToOptions.DEFAULT);
    }

    private static Double toDouble(Object value, JavaToOptions options) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof Boolean) {
            if (options.booleanAsNumber) return ((Boolean) value) ? 1d : 0d;
            throw new IllegalArgumentException("boolean cannot be converted to double without options.booleanAsNumber=true");
        }
        return Double.valueOf(String.valueOf(value).trim());
    }

    private static BigInteger toBigInteger(Object value) {
        if (value instanceof BigInteger) return (BigInteger) value;
        if (value instanceof BigDecimal) return ((BigDecimal) value).toBigInteger();
        return new BigInteger(String.valueOf(value).trim());
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof BigInteger) return new BigDecimal((BigInteger) value);
        return new BigDecimal(String.valueOf(value).trim());
    }

    private static Class<?> boxType(Class<?> type) {
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

    public static final class JavaToOptions {
        static final JavaToOptions DEFAULT = new JavaToOptions();

        final Class<?> elementType;
        final Class<?> keyType;
        final Class<?> valueType;
        final boolean deep;
        final boolean unsigned;
        final boolean nullAsEmpty;
        final boolean numberAsBoolean;
        final boolean booleanAsNumber;
        final boolean firstChar;
        final String encoding;
        final boolean hasDefaultValue;
        final Object defaultValue;

        private JavaToOptions() {
            this(null, null, null, true, false, false, false, false, false, null, false, null);
        }

        private JavaToOptions(Class<?> elementType, Class<?> keyType, Class<?> valueType, boolean deep, boolean unsigned,
                              boolean nullAsEmpty, boolean numberAsBoolean, boolean booleanAsNumber, boolean firstChar,
                              String encoding, boolean hasDefaultValue, Object defaultValue) {
            this.elementType = elementType;
            this.keyType = keyType;
            this.valueType = valueType;
            this.deep = deep;
            this.unsigned = unsigned;
            this.nullAsEmpty = nullAsEmpty;
            this.numberAsBoolean = numberAsBoolean;
            this.booleanAsNumber = booleanAsNumber;
            this.firstChar = firstChar;
            this.encoding = encoding;
            this.hasDefaultValue = hasDefaultValue;
            this.defaultValue = defaultValue;
        }

        public static JavaToOptions from(JavaBridge bridge, Object value) throws ClassNotFoundException {
            if (value == null || value == Undefined.instance || value == Scriptable.NOT_FOUND || value == Context.getUndefinedValue()) return DEFAULT;
            Class<?> elementType = readClassOption(bridge, value, "elementType");
            Class<?> keyType = readClassOption(bridge, value, "keyType");
            Class<?> valueType = readClassOption(bridge, value, "valueType");
            boolean deep = readBooleanOption(value, "deep", true);
            boolean unsigned = readBooleanOption(value, "unsigned", false);
            boolean nullAsEmpty = readBooleanOption(value, "nullAsEmpty", false);
            boolean numberAsBoolean = readBooleanOption(value, "numberAsBoolean", false);
            boolean booleanAsNumber = readBooleanOption(value, "booleanAsNumber", false);
            boolean firstChar = readBooleanOption(value, "firstChar", false);
            String encoding = readStringOption(value, "encoding", null);
            Object defaultValue = readOption(value, "defaultValue");
            boolean hasDefaultValue = defaultValue != null && defaultValue != Undefined.instance && defaultValue != Scriptable.NOT_FOUND;
            return new JavaToOptions(elementType, keyType, valueType, deep, unsigned, nullAsEmpty, numberAsBoolean,
                    booleanAsNumber, firstChar, encoding, hasDefaultValue, defaultValue);
        }

        JavaToOptions forElement() {
            return new JavaToOptions(null, null, null, deep, unsigned, nullAsEmpty, numberAsBoolean, booleanAsNumber,
                    firstChar, encoding, hasDefaultValue, defaultValue);
        }

        JavaToOptions withoutDefaultValue() {
            return new JavaToOptions(elementType, keyType, valueType, deep, unsigned, nullAsEmpty, numberAsBoolean,
                    booleanAsNumber, firstChar, encoding, false, null);
        }

        private static Class<?> readClassOption(JavaBridge bridge, Object options, String key) throws ClassNotFoundException {
            Object value = readOption(options, key);
            if (value == null || value == Undefined.instance || value == Scriptable.NOT_FOUND || value == Context.getUndefinedValue()) return null;
            return resolveType(bridge, value);
        }

        private static boolean readBooleanOption(Object options, String key, boolean fallback) {
            Object value = readOption(options, key);
            if (value == null || value == Undefined.instance || value == Scriptable.NOT_FOUND || value == Context.getUndefinedValue()) return fallback;
            Object raw = unwrapJavaLike(value);
            if (raw instanceof Boolean) return (Boolean) raw;
            String text = String.valueOf(raw).trim();
            if ("true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text)) return false;
            return fallback;
        }

        private static String readStringOption(Object options, String key, String fallback) {
            Object value = readOption(options, key);
            if (value == null || value == Undefined.instance || value == Scriptable.NOT_FOUND || value == Context.getUndefinedValue()) return fallback;
            return String.valueOf(unwrapJavaLike(value));
        }

        @SuppressWarnings("unchecked")
        private static Object readOption(Object options, String key) {
            if (options == null || options == Undefined.instance || options == Scriptable.NOT_FOUND || options == Context.getUndefinedValue()) return null;
            Object raw = unwrapJavaLike(options);
            if (raw instanceof Map<?, ?>) return ((Map<String, Object>) raw).get(key);
            if (options instanceof Scriptable) return ScriptableObject.getProperty((Scriptable) options, key);
            return null;
        }
    }
}
