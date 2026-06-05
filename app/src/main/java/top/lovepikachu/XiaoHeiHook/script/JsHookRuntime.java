package top.lovepikachu.XiaoHeiHook.script;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import top.lovepikachu.XiaoHeiHook.HookEntry;

public final class JsHookRuntime {
    private static final String TAG = "XiaoHeiHook-JS";

    private final HookEntry module;
    private final String packageName;
    private final String processName;
    private final ClassLoader appClassLoader;
    private final Object jsLock = new Object();
    private Scriptable scope;

    public JsHookRuntime(
            @NonNull HookEntry module,
            @NonNull String packageName,
            @NonNull String processName,
            @NonNull ClassLoader appClassLoader
    ) {
        this.module = module;
        this.packageName = packageName;
        this.processName = processName;
        this.appClassLoader = appClassLoader;
    }

    public void evaluate(@NonNull String scriptName, @NonNull String source) {
        synchronized (jsLock) {
            ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
            Context cx = Context.enter();
            try {
                Thread.currentThread().setContextClassLoader(appClassLoader);
                cx.setOptimizationLevel(-1);
                scope = cx.initStandardObjects();

                ScriptableObject.putProperty(scope, "env", Context.javaToJS(new Env(), scope));
                ScriptableObject.putProperty(scope, "console", Context.javaToJS(new Console(), scope));
                ScriptableObject.putProperty(scope, "Java", Context.javaToJS(new JavaBridge(), scope));
                ScriptableObject.putProperty(scope, "xposed", Context.javaToJS(new XposedBridge(), scope));

                cx.evaluateString(scope, source, scriptName, 1, null);
                log(Log.INFO, "脚本已加载: " + scriptName + " -> " + packageName + " / " + processName);
            } catch (Throwable t) {
                log(Log.ERROR, "脚本执行失败: " + scriptName, t);
            } finally {
                Thread.currentThread().setContextClassLoader(oldClassLoader);
                Context.exit();
            }
        }
    }

    public static String readRemoteText(@NonNull ParcelFileDescriptor pfd) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void log(int priority, @NonNull String msg) {
        module.log(priority, TAG, msg);
    }

    private void log(int priority, @NonNull String msg, @Nullable Throwable tr) {
        module.log(priority, TAG, msg, tr);
    }

    public final class Env {
        public final String packageName = JsHookRuntime.this.packageName;
        public final String processName = JsHookRuntime.this.processName;
        public final ClassLoader classLoader = JsHookRuntime.this.appClassLoader;
    }

    public final class Console {
        public void log(Object message) {
            JsHookRuntime.this.log(Log.INFO, String.valueOf(message));
        }

        public void warn(Object message) {
            JsHookRuntime.this.log(Log.WARN, String.valueOf(message));
        }

        public void error(Object message) {
            JsHookRuntime.this.log(Log.ERROR, String.valueOf(message));
        }
    }

    public final class JavaBridge {
        public Class<?> type(String className) throws ClassNotFoundException {
            return loadClass(className);
        }

        public Class<?> use(String className) throws ClassNotFoundException {
            return loadClass(className);
        }

        public Class<?> classForName(String className) throws ClassNotFoundException {
            return loadClass(className);
        }

        public Method method(Object clazzOrName, String methodName, Object parameterTypes) throws Exception {
            Class<?> clazz = asClass(clazzOrName);
            Class<?>[] params = toClassArray(parameterTypes);
            Method method = clazz.getDeclaredMethod(methodName, params);
            method.setAccessible(true);
            return method;
        }

        public Constructor<?> constructor(Object clazzOrName, Object parameterTypes) throws Exception {
            Class<?> clazz = asClass(clazzOrName);
            Constructor<?> constructor = clazz.getDeclaredConstructor(toClassArray(parameterTypes));
            constructor.setAccessible(true);
            return constructor;
        }

        public Field field(Object clazzOrName, String fieldName) throws Exception {
            Class<?> clazz = asClass(clazzOrName);
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        }
    }

    public final class XposedBridge {
        public void log(Object message) {
            JsHookRuntime.this.log(Log.INFO, String.valueOf(message));
        }

        public void hookMethod(Scriptable config) throws Exception {
            String className = getString(config, "className", null);
            String methodName = getString(config, "methodName", null);
            Object parameterTypes = ScriptableObject.getProperty(config, "parameterTypes");
            Object before = ScriptableObject.getProperty(config, "before");
            Object after = ScriptableObject.getProperty(config, "after");
            Object replace = ScriptableObject.getProperty(config, "replace");

            if (className == null || methodName == null) {
                throw new IllegalArgumentException("hookMethod 需要 className 和 methodName");
            }

            Method method = loadClass(className).getDeclaredMethod(methodName, toClassArray(parameterTypes));
            method.setAccessible(true);
            hookExecutable(method, before, after, replace);
            JsHookRuntime.this.log(Log.INFO, "已注册 Hook: " + className + "." + methodName);
        }

        public void hookConstructor(Scriptable config) throws Exception {
            String className = getString(config, "className", null);
            Object parameterTypes = ScriptableObject.getProperty(config, "parameterTypes");
            Object before = ScriptableObject.getProperty(config, "before");
            Object after = ScriptableObject.getProperty(config, "after");
            Object replace = ScriptableObject.getProperty(config, "replace");

            if (className == null) {
                throw new IllegalArgumentException("hookConstructor 需要 className");
            }

            Constructor<?> constructor = loadClass(className).getDeclaredConstructor(toClassArray(parameterTypes));
            constructor.setAccessible(true);
            hookExecutable(constructor, before, after, replace);
            JsHookRuntime.this.log(Log.INFO, "已注册构造器 Hook: " + className);
        }

        private void hookExecutable(Executable executable, Object before, Object after, Object replace) {
            module.hook(executable)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object[] args = chain.getArgs().toArray(new Object[0]);
                        HookParam beforeParam = new HookParam(chain.getExecutable(), chain.getThisObject(), args);

                        if (replace instanceof Function) {
                            Object replaced = callJs((Function) replace, beforeParam);
                            return Context.jsToJava(replaced, Object.class);
                        }

                        if (before instanceof Function) {
                            callJs((Function) before, beforeParam);
                            if (beforeParam.isReturnEarly()) {
                                if (beforeParam.throwable != null) throw beforeParam.throwable;
                                return beforeParam.result;
                            }
                        }

                        Object result = null;
                        Throwable throwable = null;
                        try {
                            result = chain.proceed(beforeParam.args);
                        } catch (Throwable t) {
                            throwable = t;
                        }

                        HookParam afterParam = new HookParam(chain.getExecutable(), chain.getThisObject(), beforeParam.args);
                        afterParam.result = result;
                        afterParam.throwable = throwable;

                        if (after instanceof Function) {
                            callJs((Function) after, afterParam);
                        }

                        if (afterParam.throwable != null) throw afterParam.throwable;
                        return afterParam.result;
                    });
        }
    }

    public static final class HookParam {
        public final Executable executable;
        public final Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        private boolean returnEarly = false;

        HookParam(Executable executable, Object thisObject, Object[] args) {
            this.executable = executable;
            this.thisObject = thisObject;
            this.args = args;
        }

        /**
         * 修改指定参数。before 回调中调用后会把新参数传给原方法。
         */
        public void setArg(int index, Object value) {
            if (index < 0 || index >= args.length) {
                throw new IndexOutOfBoundsException("arg index out of range: " + index + ", count=" + args.length);
            }
            args[index] = value;
        }

        public Object getArg(int index) {
            if (index < 0 || index >= args.length) {
                throw new IndexOutOfBoundsException("arg index out of range: " + index + ", count=" + args.length);
            }
            return args[index];
        }

        public int argCount() {
            return args.length;
        }

        /**
         * before 中调用：跳过原方法并直接返回 result。
         * after 中调用：覆盖最终返回值。
         */
        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public void returnResult(Object result) {
            setResult(result);
        }

        public void replaceResult(Object result) {
            setResult(result);
        }

        /**
         * before 中调用：跳过原方法并抛出 throwable。
         * after 中调用：让最终调用抛出 throwable。
         */
        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.returnEarly = true;
        }

        public void clearThrowable() {
            this.throwable = null;
        }

        public boolean hasThrowable() {
            return this.throwable != null;
        }

        public boolean isReturnEarly() {
            return returnEarly;
        }
    }

    private Object callJs(@NonNull Function function, @NonNull HookParam param) {
        synchronized (jsLock) {
            Context cx = Context.enter();
            try {
                cx.setOptimizationLevel(-1);
                Object wrapped = Context.javaToJS(param, scope);
                return function.call(cx, scope, scope, new Object[]{wrapped});
            } finally {
                Context.exit();
            }
        }
    }

    private Class<?> loadClass(String className) throws ClassNotFoundException {
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
                    return Class.forName(className, false, appClassLoader);
                } catch (ClassNotFoundException ignored) {
                    return Class.forName(className);
                }
        }
    }

    private Class<?> asClass(Object value) throws ClassNotFoundException {
        Object unwrapped = unwrap(value);
        if (unwrapped instanceof Class<?>) return (Class<?>) unwrapped;
        return loadClass(String.valueOf(unwrapped));
    }

    private Class<?>[] toClassArray(Object value) throws ClassNotFoundException {
        Object unwrapped = unwrap(value);
        if (unwrapped == null || unwrapped == Scriptable.NOT_FOUND) return new Class<?>[0];

        List<Class<?>> classes = new ArrayList<>();
        if (unwrapped instanceof NativeArray) {
            NativeArray array = (NativeArray) unwrapped;
            for (Object id : array.getIds()) {
                Object item = array.get(((Number) id).intValue(), array);
                classes.add(asClass(item));
            }
        } else if (unwrapped instanceof Object[]) {
            for (Object item : (Object[]) unwrapped) classes.add(asClass(item));
        } else if (unwrapped instanceof List<?>) {
            for (Object item : (List<?>) unwrapped) classes.add(asClass(item));
        } else if (String.valueOf(unwrapped).isBlank()) {
            return new Class<?>[0];
        } else {
            classes.add(asClass(unwrapped));
        }
        return classes.toArray(new Class<?>[0]);
    }

    private Object unwrap(Object value) {
        if (value instanceof org.mozilla.javascript.Wrapper) {
            return ((org.mozilla.javascript.Wrapper) value).unwrap();
        }
        if (value == Context.getUndefinedValue() || value == Scriptable.NOT_FOUND) return null;
        return value;
    }

    private String getString(Scriptable config, String key, String fallback) {
        Object value = ScriptableObject.getProperty(config, key);
        if (value == Scriptable.NOT_FOUND || value == Context.getUndefinedValue() || value == null) {
            return fallback;
        }
        return String.valueOf(Context.jsToJava(value, String.class));
    }
}
