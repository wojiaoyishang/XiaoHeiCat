package top.lovepikachu.XiaoHeiHook.script;

import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import top.lovepikachu.XiaoHeiHook.HookEntry;
import top.lovepikachu.XiaoHeiHook.debug.DebugProtocol;

/**
 * Rhino based JavaScript runtime for XiaoHeiHook.
 *
 * The public JS API intentionally follows modern libxposed style:
 *
 *   xposed.hook(executable)
 *       .setPriority(xposed.PRIORITY_DEFAULT)
 *       .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
 *       .intercept(function (chain) { return chain.proceed(); });
 *
 * Legacy hookMethod({ before, after, replace }) is intentionally removed.
 */
public final class JsHookRuntime {
    private static final String TAG = "XiaoHeiHook-JS";

    private static final Object DEBUG_COMMAND_LOCK = new Object();
    private static final Map<String, DebugCommand> DEBUG_COMMANDS = new ConcurrentHashMap<>();
    private static volatile boolean debugCommandReceiverRegistered = false;


    public static final String EVENT_MODULE_LOADED = "module-loaded";
    public static final String EVENT_PACKAGE_LOADED = "package-loaded";
    public static final String EVENT_PACKAGE_READY = "package-ready";
    public static final String EVENT_SYSTEM_SERVER_STARTING = "system-server-starting";

    private final HookEntry module;
    private final String packageName;
    private final String processName;
    private final ClassLoader appClassLoader;
    private final String currentEvent;
    private final Object currentRawParam;
    private final boolean rawEnabled;
    private final Object jsLock = new Object();

    @Nullable
    private android.content.Context logContext;

    private Scriptable scope;

    public JsHookRuntime(
            @NonNull HookEntry module,
            @NonNull String packageName,
            @NonNull String processName,
            @NonNull ClassLoader appClassLoader,
            @NonNull String currentEvent,
            @Nullable Object currentRawParam
    ) {
        this(module, packageName, processName, appClassLoader, currentEvent, currentRawParam, true);
    }

    public JsHookRuntime(
            @NonNull HookEntry module,
            @NonNull String packageName,
            @NonNull String processName,
            @NonNull ClassLoader appClassLoader,
            @NonNull String currentEvent,
            @Nullable Object currentRawParam,
            boolean rawEnabled
    ) {
        this.module = module;
        this.packageName = packageName;
        this.processName = processName;
        this.appClassLoader = appClassLoader;
        this.currentEvent = currentEvent;
        this.currentRawParam = currentRawParam;
        this.rawEnabled = rawEnabled;
    }

    public void evaluate(@NonNull String scriptName, @NonNull String source) {
        synchronized (jsLock) {
            ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
            Context cx = Context.enter();
            try {
                Thread.currentThread().setContextClassLoader(appClassLoader);
                cx.setOptimizationLevel(-1);
                scope = cx.initStandardObjects();

                ScriptableObject.putProperty(scope, "env", Context.javaToJS(new Env(scriptName), scope));
                ScriptableObject.putProperty(scope, "console", Context.javaToJS(new Console(), scope));
                ScriptableObject.putProperty(scope, "Java", Context.javaToJS(new JavaBridge(), scope));
                ScriptableObject.putProperty(scope, "xposed", Context.javaToJS(new XposedFacade(), scope));
                ScriptableObject.putProperty(scope, "debuggerx", Context.javaToJS(new DebuggerFacade(scriptName), scope));

                cx.evaluateString(scope, source, scriptName, 1, null);
                log(Log.INFO, "脚本已加载: " + scriptName + " -> " + packageName + " / " + processName + " @" + currentEvent);
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
        writeLog(priority, TAG, msg, null);
    }

    private void log(int priority, @NonNull String msg, @Nullable Throwable tr) {
        writeLog(priority, TAG, msg, tr);
    }

    private void writeLog(int priority, @Nullable String tag, @NonNull String msg, @Nullable Throwable tr) {
        String safeTag = tag == null || tag.isEmpty() ? TAG : tag;
        try {
            if (tr == null) {
                module.log(priority, safeTag, msg);
            } else {
                module.log(priority, safeTag, msg, tr);
            }
        } catch (Throwable ignored) {
            // Logging must never break hook execution.
        }
        appendAppLog(priority, safeTag, msg, tr);
    }

    private void appendAppLog(int priority, @NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = time + " " + priorityName(priority) + "/" + tag + " [" + processName + "] " + msg + "\n";
        if (tr != null) {
            StringWriter sw = new StringWriter();
            tr.printStackTrace(new PrintWriter(sw));
            line += sw.toString() + "\n";
        }

        // Robust path only: relay logs back to XiaoHeiHook app by explicit broadcast.
        // The receiver stores logs in XiaoHeiHook's own private directory, which is what the
        // built-in terminal reads. We intentionally do not write into the target app's private dir.
        sendLogBroadcast(priority, tag, msg, tr, line);
    }

    private void sendLogBroadcast(int priority, @NonNull String tag, @NonNull String msg, @Nullable Throwable tr, @NonNull String line) {
        try {
            android.content.Context context = resolveBroadcastContext();
            if (context == null) return;
            android.content.Intent intent = new android.content.Intent("top.lovepikachu.XiaoHeiHook.LOG_EVENT");
            intent.setComponent(new android.content.ComponentName(
                    "top.lovepikachu.XiaoHeiHook",
                    "top.lovepikachu.XiaoHeiHook.data.LogReceiver"
            ));
            intent.putExtra("packageName", packageName);
            intent.putExtra("processName", processName);
            intent.putExtra("priority", priority);
            intent.putExtra("tag", tag);
            intent.putExtra("message", msg);
            intent.putExtra("line", line);
            if (tr != null) {
                StringWriter sw = new StringWriter();
                tr.printStackTrace(new PrintWriter(sw));
                intent.putExtra("throwable", sw.toString());
            }
            context.sendBroadcast(intent);
        } catch (Throwable broadcastError) {
            try {
                module.log(Log.WARN, TAG, "发送日志广播失败: " + broadcastError);
            } catch (Throwable ignored) {
            }
        }
    }

    private void sendDebugEvent(@NonNull JSONObject event) {
        try {
            android.content.Context context = resolveBroadcastContext();
            if (context == null) {
                log(Log.WARN, "调试事件发送失败：无法获取 Context");
                return;
            }
            Intent intent = new Intent(DebugProtocol.ACTION_EVENT);
            intent.setComponent(new android.content.ComponentName(
                    "top.lovepikachu.XiaoHeiHook",
                    "top.lovepikachu.XiaoHeiHook.debug.DebugEventReceiver"
            ));
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra(DebugProtocol.EXTRA_EVENT_JSON, event.toString());
            intent.putExtra(DebugProtocol.EXTRA_PACKAGE_NAME, packageName);
            intent.putExtra(DebugProtocol.EXTRA_PROCESS_NAME, processName);
            intent.putExtra(DebugProtocol.EXTRA_PAUSE_ID, event.optString("pauseId"));
            context.sendBroadcast(intent);
        } catch (Throwable broadcastError) {
            try {
                module.log(Log.WARN, TAG, "发送调试事件失败: " + broadcastError);
            } catch (Throwable ignored) {
            }
        }
    }

    @Nullable
    private android.content.Context resolveBroadcastContext() {
        if (logContext != null) return logContext;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            if (application instanceof android.content.Context) {
                logContext = (android.content.Context) application;
                return logContext;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object thread = currentActivityThread.invoke(null);
            if (thread != null) {
                Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
                getSystemContext.setAccessible(true);
                Object context = getSystemContext.invoke(thread);
                if (context instanceof android.content.Context) {
                    logContext = (android.content.Context) context;
                    return logContext;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void rememberLogContext(@Nullable Object value) {
        Object unwrapped = unwrap(value);
        if (unwrapped instanceof android.content.Context) {
            logContext = (android.content.Context) unwrapped;
        }
    }

    private static String priorityName(int priority) {
        switch (priority) {
            case Log.VERBOSE: return "V";
            case Log.DEBUG: return "D";
            case Log.INFO: return "I";
            case Log.WARN: return "W";
            case Log.ERROR: return "E";
            case Log.ASSERT: return "A";
            default: return String.valueOf(priority);
        }
    }

    private Object callJs(@NonNull Function function, @Nullable Object... args) throws Throwable {
        synchronized (jsLock) {
            ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
            Context cx = Context.enter();
            try {
                Thread.currentThread().setContextClassLoader(appClassLoader);
                cx.setOptimizationLevel(-1);
                Object[] wrappedArgs = new Object[args == null ? 0 : args.length];
                for (int i = 0; i < wrappedArgs.length; i++) {
                    wrappedArgs[i] = Context.javaToJS(args[i], scope);
                }
                return function.call(cx, scope, scope, wrappedArgs);
            } finally {
                Thread.currentThread().setContextClassLoader(oldClassLoader);
                Context.exit();
            }
        }
    }

    private Object jsToJavaValue(Object value) {
        Object unwrapped = unwrap(value);
        if (unwrapped == Undefined.instance || unwrapped == Scriptable.NOT_FOUND) return null;
        return Context.jsToJava(unwrapped, Object.class);
    }

    public final class Env {
        public final String scriptName;
        public final String packageName = JsHookRuntime.this.packageName;
        public final String processName = JsHookRuntime.this.processName;
        public final ClassLoader classLoader = JsHookRuntime.this.appClassLoader;
        public final Object raw = rawEnabled ? currentRawParam : null;

        Env(String scriptName) {
            this.scriptName = scriptName;
        }
    }

    private String joinLogParts(Object... messages) {
        if (messages == null || messages.length == 0) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < messages.length; i++) {
            if (i > 0) builder.append(' ');
            builder.append(String.valueOf(unwrap(messages[i])));
        }
        return builder.toString();
    }

    public final class Console {
        public void log(Object... messages) {
            JsHookRuntime.this.log(Log.DEBUG, joinLogParts(messages));
        }

        public void info(Object... messages) {
            JsHookRuntime.this.log(Log.INFO, joinLogParts(messages));
        }

        public void warn(Object... messages) {
            JsHookRuntime.this.log(Log.WARN, joinLogParts(messages));
        }

        public void error(Object... messages) {
            Throwable throwable = null;
            if (messages != null && messages.length > 0) {
                Object last = unwrap(messages[messages.length - 1]);
                if (last instanceof Throwable) throwable = (Throwable) last;
            }
            JsHookRuntime.this.log(Log.ERROR, joinLogParts(messages), throwable);
        }
    }

    public final class DebuggerFacade {
        private final String scriptName;

        DebuggerFacade(String scriptName) {
            this.scriptName = scriptName;
        }

        public boolean isEnabled() {
            return isSoftDebuggerEnabled();
        }

        public Object pause(String name) {
            return breakpoint(name, null);
        }

        public Object breakpoint(String name) {
            return breakpoint(name, null);
        }

        public Object breakpoint(String name, Object locals) {
            return hitSoftBreakpoint(scriptName, name, locals);
        }

        public void log(String name, Object value) {
            if (!isSoftDebuggerEnabled()) return;
            JSONObject event = buildDebugBaseEvent("log", scriptName);
            try {
                event.put("name", String.valueOf(name));
                event.put("value", toDebugJsonValue(value, 0));
            } catch (Throwable ignored) {
            }
            sendDebugEvent(event);
        }
    }

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

        public Class<?> type(String className) throws ClassNotFoundException {
            return loadClass(className);
        }

        public Class<?> use(String className) throws ClassNotFoundException {
            return loadClass(className);
        }

        public Class<?> classForName(String className) throws ClassNotFoundException {
            return loadClass(className);
        }

        public Class<?> classForName(String className, boolean initialize, ClassLoader loader) throws ClassNotFoundException {
            return Class.forName(className, initialize, loader == null ? appClassLoader : loader);
        }

        public Method method(Object clazzOrName, String methodName) throws Exception {
            return method(clazzOrName, methodName, null);
        }

        public Method method(Object clazzOrName, String methodName, Object parameterTypes) throws Exception {
            Class<?> clazz = asClass(clazzOrName);
            Method method = clazz.getDeclaredMethod(methodName, toClassArray(parameterTypes));
            method.setAccessible(true);
            return method;
        }

        public Constructor<?> constructor(Object clazzOrName) throws Exception {
            return constructor(clazzOrName, null);
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

        public Object newArray(Object componentType, int length) throws Exception {
            return java.lang.reflect.Array.newInstance(asClass(componentType), length);
        }
    }

    public final class XposedFacade {
        public final int PRIORITY_HIGHEST = intConst("PRIORITY_HIGHEST", 10000);
        public final int PRIORITY_DEFAULT = intConst("PRIORITY_DEFAULT", 0);
        public final int PRIORITY_LOWEST = intConst("PRIORITY_LOWEST", -10000);

        public final long PROP_CAP_SYSTEM = longConst("PROP_CAP_SYSTEM", 0L);
        public final long PROP_CAP_REMOTE = longConst("PROP_CAP_REMOTE", 0L);
        public final long PROP_RT_API_PROTECTION = longConst("PROP_RT_API_PROTECTION", 0L);

        public final ExceptionModeFacade ExceptionMode = new ExceptionModeFacade();
        public final LogFacade Log = new LogFacade();
        public final RawFacade raw = new RawFacade();

        public void onModuleLoaded(Function callback) throws Throwable {
            if (EVENT_MODULE_LOADED.equals(currentEvent)) callLifecycle(callback, new LifecycleParam(currentRawParam));
        }

        public void onPackageLoaded(Function callback) throws Throwable {
            if (EVENT_PACKAGE_LOADED.equals(currentEvent)) callLifecycle(callback, new LifecycleParam(currentRawParam));
        }

        public void onPackageReady(Function callback) throws Throwable {
            if (EVENT_PACKAGE_READY.equals(currentEvent)) callLifecycle(callback, new LifecycleParam(currentRawParam));
        }

        public void onSystemServerStarting(Function callback) throws Throwable {
            if (EVENT_SYSTEM_SERVER_STARTING.equals(currentEvent)) callLifecycle(callback, new LifecycleParam(currentRawParam));
        }

        private void callLifecycle(Function callback, LifecycleParam param) throws Throwable {
            if (callback != null) callJs(callback, param);
        }

        public JsHookBuilder hook(Executable executable) {
            return new JsHookBuilder(module.hook(executable));
        }

        public JsHookBuilder hookClassInitializer(Class<?> clazz) {
            return new JsHookBuilder(module.hookClassInitializer(clazz));
        }

        public boolean deoptimize(Executable executable) {
            return module.deoptimize(executable);
        }

        public JsInvoker getInvoker(Method method) {
            return new JsInvoker(module.getInvoker(method));
        }

        public JsInvoker getInvoker(Constructor<?> constructor) {
            return new JsInvoker(module.getInvoker(constructor));
        }

        public void log(int priority, String tag, Object msg) {
            JsHookRuntime.this.writeLog(priority, tag, String.valueOf(msg), null);
        }

        public void log(int priority, String tag, Object msg, Object throwable) {
            JsHookRuntime.this.writeLog(priority, tag, String.valueOf(msg), toThrowable(throwable));
        }

        public void v(String tag, Object msg) { log(Log.VERBOSE, tag, msg); }
        public void d(String tag, Object msg) { log(Log.DEBUG, tag, msg); }
        public void i(String tag, Object msg) { log(Log.INFO, tag, msg); }
        public void w(String tag, Object msg) { log(Log.WARN, tag, msg); }
        public void e(String tag, Object msg) { log(Log.ERROR, tag, msg); }
        public void e(String tag, Object msg, Object throwable) { log(Log.ERROR, tag, msg, throwable); }
        public void wtf(String tag, Object msg) { log(Log.ASSERT, tag, msg); }

        public Object getApiVersion() { return invokeNoArg(module, "getApiVersion"); }
        public Object getFrameworkName() { return invokeNoArg(module, "getFrameworkName"); }
        public Object getFrameworkVersion() { return invokeNoArg(module, "getFrameworkVersion"); }
        public Object getFrameworkVersionCode() { return invokeNoArg(module, "getFrameworkVersionCode"); }
        public Object getFrameworkProperties() { return invokeNoArg(module, "getFrameworkProperties"); }
        public Object getModuleApplicationInfo() { return invokeNoArg(module, "getModuleApplicationInfo"); }

        public SharedPreferences getRemotePreferences(String group) {
            return module.getRemotePreferences(group);
        }

        public String[] listRemoteFiles() throws Exception {
            Object value = invokeNoArg(module, "listRemoteFiles");
            if (value instanceof String[]) return (String[]) value;
            if (value instanceof List<?>) {
                List<?> list = (List<?>) value;
                String[] result = new String[list.size()];
                for (int i = 0; i < list.size(); i++) result[i] = String.valueOf(list.get(i));
                return result;
            }
            return new String[0];
        }

        public ParcelFileDescriptor openRemoteFile(String name) {
            try {
                return module.openRemoteFile(name);
            } catch (Throwable t) {
                throw new RuntimeException("openRemoteFile failed: " + name, t);
            }
        }

        public String readRemoteText(String name) throws Exception {
            return JsHookRuntime.readRemoteText(openRemoteFile(name));
        }
    }

    public final class ExceptionModeFacade {
        public final Object DEFAULT = enumValue(XposedInterface.ExceptionMode.class, "DEFAULT");
        public final Object PROTECTIVE = enumValue(XposedInterface.ExceptionMode.class, "PROTECTIVE");
        public final Object PASSTHROUGH = enumValue(XposedInterface.ExceptionMode.class, "PASSTHROUGH");
    }

    public static final class LogFacade {
        public final int VERBOSE = Log.VERBOSE;
        public final int DEBUG = Log.DEBUG;
        public final int INFO = Log.INFO;
        public final int WARN = Log.WARN;
        public final int ERROR = Log.ERROR;
        public final int ASSERT = Log.ASSERT;
    }

    public final class RawFacade {
        public final boolean enabled = rawEnabled;
        public final Object interfaceObject = rawEnabled ? JsHookRuntime.this.module : null;
        public final Object module = rawEnabled ? JsHookRuntime.this.module : null;
        public final Object currentParam = rawEnabled ? currentRawParam : null;
        public final Object currentPackageLoadedParam = rawEnabled && EVENT_PACKAGE_LOADED.equals(currentEvent) ? currentRawParam : null;
        public final Object currentPackageReadyParam = rawEnabled && EVENT_PACKAGE_READY.equals(currentEvent) ? currentRawParam : null;
        public final Object currentModuleLoadedParam = rawEnabled && EVENT_MODULE_LOADED.equals(currentEvent) ? currentRawParam : null;
        public final Object currentSystemServerStartingParam = rawEnabled && EVENT_SYSTEM_SERVER_STARTING.equals(currentEvent) ? currentRawParam : null;
        public final ClassLoader classLoader = appClassLoader;
        public final String packageName = JsHookRuntime.this.packageName;
        public final String processName = JsHookRuntime.this.processName;

        public boolean enabled() { return rawEnabled; }
        public Object getInterface() { return interfaceObject; }
        public Object unwrap(Object value) { return JsHookRuntime.this.unwrap(value); }
        public String typeOf(Object value) { Object v = unwrap(value); return v == null ? "null" : v.getClass().getName(); }

        public Object call(Object target, String methodName) throws Exception { return call(target, methodName, null); }

        public Object call(Object target, String methodName, Object argsObj) throws Exception {
            Object unwrappedTarget = unwrap(target);
            Object[] args = toObjectArray(argsObj);
            Method method = findCompatibleMethod(unwrappedTarget.getClass(), methodName, args.length, false);
            method.setAccessible(true);
            return method.invoke(unwrappedTarget, args);
        }

        public Field field(Object targetOrClass, String fieldName) throws Exception {
            Class<?> clazz = targetOrClass instanceof Class<?> ? (Class<?>) targetOrClass : unwrap(targetOrClass).getClass();
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        }

        public Method method(Object clazzOrName, String methodName) throws Exception {
            return findCompatibleMethod(asClass(clazzOrName), methodName, -1, false);
        }

        public Method method(Object clazzOrName, String methodName, Object parameterTypes) throws Exception {
            Class<?> clazz = asClass(clazzOrName);
            Method method = clazz.getDeclaredMethod(methodName, toClassArray(parameterTypes));
            method.setAccessible(true);
            return method;
        }

        public Constructor<?> constructor(Object clazzOrName) throws Exception {
            return findCompatibleConstructor(asClass(clazzOrName), -1);
        }

        public Constructor<?> constructor(Object clazzOrName, Object parameterTypes) throws Exception {
            Class<?> clazz = asClass(clazzOrName);
            Constructor<?> constructor = clazz.getDeclaredConstructor(toClassArray(parameterTypes));
            constructor.setAccessible(true);
            return constructor;
        }
    }

    public final class JsHookBuilder {
        public final Object raw;
        private final XposedInterface.HookBuilder builder;

        JsHookBuilder(XposedInterface.HookBuilder builder) {
            this.builder = builder;
            this.raw = rawEnabled ? builder : null;
        }

        public JsHookBuilder setPriority(int priority) {
            builder.setPriority(priority);
            return this;
        }

        public JsHookBuilder setExceptionMode(Object mode) {
            XposedInterface.ExceptionMode converted = toExceptionMode(mode);
            if (converted != null) builder.setExceptionMode(converted);
            return this;
        }

        public JsHookHandle intercept(Function callback) {
            XposedInterface.HookHandle handle = builder.intercept(chain -> {
                try {
                    Object jsResult = callJs(callback, new JsChain(chain));
                    return jsToJavaValue(jsResult);
                } catch (Throwable t) {
                    log(Log.ERROR, "Hook 回调执行失败: " + chain.getExecutable(), t);
                    throw t;
                }
            });
            return new JsHookHandle(handle);
        }
    }

    public final class JsHookHandle {
        public final Object raw;
        private final XposedInterface.HookHandle handle;

        JsHookHandle(XposedInterface.HookHandle handle) {
            this.handle = handle;
            this.raw = rawEnabled ? handle : null;
        }

        public Executable getExecutable() { return handle.getExecutable(); }
        public void unhook() { handle.unhook(); }
    }

    public final class JsChain {
        public final Object raw;
        private final XposedInterface.Chain chain;

        JsChain(XposedInterface.Chain chain) {
            this.chain = chain;
            this.raw = rawEnabled ? chain : null;
            rememberLogContext(chain.getThisObject());
            try {
                for (Object arg : chain.getArgs()) rememberLogContext(arg);
            } catch (Throwable ignored) {
            }
        }

        public Executable getExecutable() { return chain.getExecutable(); }
        public Object getThisObject() {
            Object value = chain.getThisObject();
            rememberLogContext(value);
            return value;
        }
        public List<?> getArgs() { return chain.getArgs(); }
        public Object getArg(int index) {
            Object value = chain.getArg(index);
            rememberLogContext(value);
            return value;
        }
        public Object[] getArgsMutable() { return chain.getArgs().toArray(new Object[0]); }
        public Object proceed() throws Throwable { return chain.proceed(); }
        public Object proceed(Object argsObj) throws Throwable { return chain.proceed(toObjectArray(argsObj)); }
        public Object proceedWith(Object thisObject) throws Throwable { return chain.proceedWith(unwrap(thisObject)); }
        public Object proceedWith(Object thisObject, Object argsObj) throws Throwable { return chain.proceedWith(unwrap(thisObject), toObjectArray(argsObj)); }
    }

    public final class JsInvoker {
        public final Object raw;
        private final Object invoker;

        JsInvoker(Object invoker) {
            this.invoker = invoker;
            this.raw = rawEnabled ? invoker : null;
        }

        public JsInvoker setType(Object type) throws Exception {
            Method method = findCompatibleMethod(invoker.getClass(), "setType", 1, false);
            method.setAccessible(true);
            method.invoke(invoker, unwrap(type));
            return this;
        }

        public Object invoke(Object thisObject) throws Exception { return invoke(thisObject, null); }

        public Object invoke(Object thisObject, Object argsObj) throws Exception {
            return invokeReflective("invoke", unwrap(thisObject), argsObj);
        }

        public Object invokeSpecial(Object thisObject) throws Exception { return invokeSpecial(thisObject, null); }

        public Object invokeSpecial(Object thisObject, Object argsObj) throws Exception {
            return invokeReflective("invokeSpecial", unwrap(thisObject), argsObj);
        }

        public Object newInstance() throws Exception { return newInstance(null); }

        public Object newInstance(Object argsObj) throws Exception {
            return invokeReflective("newInstance", null, argsObj);
        }

        public Object newInstanceSpecial(Class<?> subClass) throws Exception { return newInstanceSpecial(subClass, null); }

        public Object newInstanceSpecial(Class<?> subClass, Object argsObj) throws Exception {
            return invokeReflective("newInstanceSpecial", subClass, argsObj);
        }

        private Object invokeReflective(String name, Object firstArg, Object argsObj) throws Exception {
            Object[] args = toObjectArray(argsObj);
            for (Method method : invoker.getClass().getMethods()) {
                if (!method.getName().equals(name)) continue;
                method.setAccessible(true);
                Class<?>[] types = method.getParameterTypes();
                if (types.length == 1 && types[0].isArray()) {
                    return method.invoke(invoker, new Object[]{args});
                }
                if (types.length == 2 && types[1].isArray()) {
                    return method.invoke(invoker, firstArg, args);
                }
                if (types.length == args.length) {
                    return method.invoke(invoker, args);
                }
                if (types.length == args.length + 1) {
                    Object[] full = new Object[args.length + 1];
                    full[0] = firstArg;
                    System.arraycopy(args, 0, full, 1, args.length);
                    return method.invoke(invoker, full);
                }
            }
            throw new NoSuchMethodException(invoker.getClass().getName() + "." + name);
        }
    }

    public final class LifecycleParam {
        public final Object raw;

        LifecycleParam(@Nullable Object raw) {
            this.raw = rawEnabled ? raw : null;
        }

        public String getPackageName() { return String.valueOf(invokeNoArg(currentRawParam, "getPackageName")); }
        public Object getApplicationInfo() { return invokeNoArg(currentRawParam, "getApplicationInfo"); }
        public boolean isFirstPackage() { return Boolean.TRUE.equals(invokeNoArg(currentRawParam, "isFirstPackage")); }
        public ClassLoader getDefaultClassLoader() { return (ClassLoader) invokeNoArg(currentRawParam, "getDefaultClassLoader"); }
        public ClassLoader getClassLoader() {
            Object value = invokeNoArg(currentRawParam, "getClassLoader");
            return value instanceof ClassLoader ? (ClassLoader) value : getDefaultClassLoader();
        }
        public Object getAppComponentFactory() { return invokeNoArg(currentRawParam, "getAppComponentFactory"); }
        public String getProcessName() {
            Object value = invokeNoArg(currentRawParam, "getProcessName");
            return value == null ? processName : String.valueOf(value);
        }
        public boolean isSystemServer() { return Boolean.TRUE.equals(invokeNoArg(currentRawParam, "isSystemServer")); }
    }


    private boolean isSoftDebuggerEnabled() {
        try {
            SharedPreferences prefs = module.getRemotePreferences(DebugProtocol.PREF_GROUP);
            if (prefs == null) return false;
            return prefs.getBoolean(DebugProtocol.debugEnabledKey(packageName), false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object hitSoftBreakpoint(@NonNull String scriptName, @Nullable String name, @Nullable Object locals) {
        if (!isSoftDebuggerEnabled()) {
            return null;
        }
        android.content.Context context = resolveBroadcastContext();
        if (context == null) {
            log(Log.WARN, "debuggerx.breakpoint 已忽略：无法获取 Context，建议在 Application.attach 之后使用断点");
            return null;
        }
        ensureDebugCommandReceiver(context);

        String pauseId = UUID.randomUUID().toString();
        JSONObject event = buildDebugBaseEvent("paused", scriptName);
        try {
            event.put("pauseId", pauseId);
            event.put("breakpointName", name == null || name.isBlank() ? "soft-breakpoint" : name);
            event.put("threadName", Thread.currentThread().getName());
            event.put("status", "paused");
            event.put("updatedAt", System.currentTimeMillis());
            event.put("locals", toDebugJsonValue(locals, 0));
            event.put("editableLocals", locals != null && locals != Undefined.instance && locals != Scriptable.NOT_FOUND);
        } catch (Throwable ignored) {
        }

        sendDebugEvent(event);
        writeLog(Log.WARN, "XHH-Debug", "命中软断点，目标线程已暂停: " + event.optString("breakpointName") + " pauseId=" + pauseId, null);

        while (true) {
            DebugCommand command = waitForDebugCommand(pauseId);
            if (command == null) {
                command = new DebugCommand(DebugProtocol.COMMAND_CONTINUE, null, new JSONObject());
            }

            if (DebugProtocol.COMMAND_SET_VARIABLE.equals(command.command)) {
                JSONObject updated = buildDebugBaseEvent("variablesUpdated", scriptName);
                try {
                    applyDebugVariablePayload(locals, command.payload);
                    updated.put("pauseId", pauseId);
                    updated.put("breakpointName", event.optString("breakpointName"));
                    updated.put("locals", toDebugJsonValue(locals, 0));
                    updated.put("message", "locals updated");
                } catch (Throwable updateError) {
                    try {
                        updated.put("error", updateError.getMessage() == null ? updateError.getClass().getName() : updateError.getMessage());
                        updated.put("locals", toDebugJsonValue(locals, 0));
                    } catch (Throwable ignored) {
                    }
                }
                sendDebugEvent(updated);
                continue;
            }

            if (DebugProtocol.COMMAND_CONTINUE.equals(command.command)) {
                JSONObject continued = buildDebugBaseEvent("continued", scriptName);
                try {
                    continued.put("pauseId", pauseId);
                    continued.put("breakpointName", event.optString("breakpointName"));
                    continued.put("status", "resumed");
                    continued.put("updatedAt", System.currentTimeMillis());
                    continued.put("hasReturnValue", command.payload.optBoolean("hasReturnValue", false));
                    if (command.payload.has("returnValue")) continued.put("returnValue", command.payload.opt("returnValue"));
                } catch (Throwable ignored) {
                }
                sendDebugEvent(continued);
                writeLog(Log.INFO, "XHH-Debug", "软断点继续执行: pauseId=" + pauseId, null);
                if (command.payload.optBoolean("hasReturnValue", false)) {
                    return jsonToJsValue(command.payload.opt("returnValue"));
                }
                return null;
            }

            if (DebugProtocol.COMMAND_ABORT.equals(command.command)) {
                JSONObject aborted = buildDebugBaseEvent("aborted", scriptName);
                try {
                    aborted.put("pauseId", pauseId);
                    aborted.put("breakpointName", event.optString("breakpointName"));
                    aborted.put("status", "aborted");
                    aborted.put("updatedAt", System.currentTimeMillis());
                } catch (Throwable ignored) {
                }
                sendDebugEvent(aborted);
                throw new RuntimeException("Debug session aborted: " + pauseId);
            }
        }
    }

    private JSONObject buildDebugBaseEvent(@NonNull String type, @NonNull String scriptName) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("type", type);
            obj.put("time", System.currentTimeMillis());
            obj.put("packageName", packageName);
            obj.put("processName", processName);
            obj.put("scriptName", scriptName);
            obj.put("event", currentEvent);
            obj.put("pid", android.os.Process.myPid());
        } catch (Throwable ignored) {
        }
        return obj;
    }

    private DebugCommand waitForDebugCommand(@NonNull String pauseId) {
        while (true) {
            DebugCommand remoteCommand = readRemoteDebugCommand(pauseId);
            if (remoteCommand != null) return remoteCommand;

            synchronized (DEBUG_COMMAND_LOCK) {
                DebugCommand command = DEBUG_COMMANDS.remove(pauseId);
                if (command != null) return command;
                try {
                    DEBUG_COMMAND_LOCK.wait(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new DebugCommand(DebugProtocol.COMMAND_ABORT, null, new JSONObject());
                }
            }
        }
    }

    @Nullable
    private DebugCommand readRemoteDebugCommand(@NonNull String pauseId) {
        try {
            SharedPreferences prefs = module.getRemotePreferences(DebugProtocol.PREF_GROUP);
            if (prefs == null) return null;
            String raw = prefs.getString(DebugProtocol.debugCommandKey(pauseId), "");
            if (raw == null || raw.isBlank()) return null;
            try {
                prefs.edit().remove(DebugProtocol.debugCommandKey(pauseId)).commit();
            } catch (Throwable ignored) {
            }
            JSONObject obj = new JSONObject(raw);
            String command = obj.optString("command", DebugProtocol.COMMAND_CONTINUE);
            String expression = obj.optString("expression", null);
            JSONObject payload = obj.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();
            if (command == null || command.isBlank()) command = DebugProtocol.COMMAND_CONTINUE;
            return new DebugCommand(command, expression, payload);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void ensureDebugCommandReceiver(@NonNull android.content.Context context) {
        if (debugCommandReceiverRegistered) return;
        synchronized (JsHookRuntime.class) {
            if (debugCommandReceiverRegistered) return;
            android.content.Context appContext = context.getApplicationContext() == null ? context : context.getApplicationContext();
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context ctx, Intent intent) {
                    if (intent == null || !DebugProtocol.ACTION_COMMAND.equals(intent.getAction())) return;
                    String pauseId = intent.getStringExtra(DebugProtocol.EXTRA_PAUSE_ID);
                    String command = intent.getStringExtra(DebugProtocol.EXTRA_COMMAND);
                    String expression = intent.getStringExtra(DebugProtocol.EXTRA_EXPRESSION);
                    String payloadJson = intent.getStringExtra(DebugProtocol.EXTRA_PAYLOAD_JSON);
                    JSONObject payload = new JSONObject();
                    if (payloadJson != null && !payloadJson.isBlank()) {
                        try { payload = new JSONObject(payloadJson); } catch (Throwable ignored) { }
                    }
                    if (pauseId == null || pauseId.isBlank()) return;
                    if (command == null || command.isBlank()) command = DebugProtocol.COMMAND_CONTINUE;
                    DEBUG_COMMANDS.put(pauseId, new DebugCommand(command, expression, payload));
                    synchronized (DEBUG_COMMAND_LOCK) {
                        DEBUG_COMMAND_LOCK.notifyAll();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(DebugProtocol.ACTION_COMMAND);
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(receiver, filter);
            }
            debugCommandReceiverRegistered = true;
            writeLog(Log.INFO, "XHH-Debug", "调试命令接收器已注册: " + packageName + " / " + processName, null);
        }
    }

    private Object toDebugJsonValue(@Nullable Object value, int depth) {
        Object v = unwrap(value);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return JSONObject.NULL;
        if (depth > 4) return String.valueOf(v);
        if (v instanceof String || v instanceof Number || v instanceof Boolean) return v;
        if (v instanceof Character) return String.valueOf(v);
        if (v instanceof Class<?>) return ((Class<?>) v).getName();
        if (v instanceof Method || v instanceof Constructor<?> || v instanceof Field) return String.valueOf(v);
        if (v instanceof Throwable) {
            Throwable t = (Throwable) v;
            JSONObject obj = new JSONObject();
            try {
                obj.put("type", t.getClass().getName());
                obj.put("message", t.getMessage());
                obj.put("text", String.valueOf(t));
            } catch (Throwable ignored) {
            }
            return obj;
        }
        if (v instanceof NativeArray) {
            NativeArray array = (NativeArray) v;
            JSONArray json = new JSONArray();
            long len = Math.min(array.getLength(), 200);
            for (int i = 0; i < len; i++) {
                try {
                    json.put(toDebugJsonValue(array.get(i, array), depth + 1));
                } catch (Throwable ignored) {
                    json.put(String.valueOf(array.get(i, array)));
                }
            }
            return json;
        }
        if (v instanceof Scriptable) {
            Scriptable scriptable = (Scriptable) v;
            JSONObject obj = new JSONObject();
            for (Object id : scriptable.getIds()) {
                try {
                    String key = String.valueOf(id);
                    Object child = id instanceof Number
                            ? scriptable.get(((Number) id).intValue(), scriptable)
                            : scriptable.get(key, scriptable);
                    obj.put(key, toDebugJsonValue(child, depth + 1));
                } catch (Throwable ignored) {
                }
            }
            return obj;
        }
        if (v instanceof Map<?, ?>) {
            JSONObject obj = new JSONObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) v).entrySet()) {
                try {
                    obj.put(String.valueOf(entry.getKey()), toDebugJsonValue(entry.getValue(), depth + 1));
                } catch (Throwable ignored) {
                }
            }
            return obj;
        }
        if (v instanceof Iterable<?>) {
            JSONArray json = new JSONArray();
            int i = 0;
            for (Object item : (Iterable<?>) v) {
                if (i++ >= 200) break;
                json.put(toDebugJsonValue(item, depth + 1));
            }
            return json;
        }
        if (v.getClass().isArray()) {
            JSONArray json = new JSONArray();
            int len = Math.min(java.lang.reflect.Array.getLength(v), 200);
            for (int i = 0; i < len; i++) {
                json.put(toDebugJsonValue(java.lang.reflect.Array.get(v, i), depth + 1));
            }
            return json;
        }
        return String.valueOf(v);
    }


    private void applyDebugVariablePayload(@Nullable Object locals, @NonNull JSONObject payload) throws Exception {
        if (locals == null || locals == Undefined.instance || locals == Scriptable.NOT_FOUND) {
            throw new IllegalStateException("当前断点没有可修改的 locals。请使用 debuggerx.breakpoint(name, { key: value }) 传入对象。");
        }
        if (payload.has("path")) {
            String path = payload.optString("path", "").trim();
            if (path.isEmpty()) throw new IllegalArgumentException("变量路径不能为空");
            Object value = payload.has("value") ? payload.opt("value") : JSONObject.NULL;
            setDebugPathValue(unwrap(locals), parseDebugPath(path), jsonToJsValue(value));
            return;
        }
        if (payload.has("locals")) {
            Object next = payload.opt("locals");
            applyDebugObjectValue(unwrap(locals), next);
            return;
        }
        throw new IllegalArgumentException("缺少 path/value 或 locals 字段");
    }

    private List<String> parseDebugPath(@NonNull String path) {
        ArrayList<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else if (c == '[') {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                int end = path.indexOf(']', i + 1);
                if (end < 0) throw new IllegalArgumentException("变量路径格式错误: " + path);
                String index = path.substring(i + 1, end).trim();
                if ((index.startsWith("\"") && index.endsWith("\"")) || (index.startsWith("'") && index.endsWith("'"))) {
                    index = index.substring(1, index.length() - 1);
                }
                if (!index.isEmpty()) parts.add(index);
                i = end;
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) parts.add(current.toString());
        if (parts.isEmpty()) throw new IllegalArgumentException("变量路径不能为空");
        return parts;
    }

    private void setDebugPathValue(@Nullable Object root, @NonNull List<String> parts, @Nullable Object value) throws Exception {
        Object target = root;
        for (int i = 0; i < parts.size() - 1; i++) {
            target = getDebugChild(target, parts.get(i));
            if (target == null || target == Scriptable.NOT_FOUND || target == Undefined.instance) {
                throw new IllegalArgumentException("变量路径不存在: " + parts.get(i));
            }
            target = unwrap(target);
        }
        setDebugChild(target, parts.get(parts.size() - 1), value);
    }

    @Nullable
    private Object getDebugChild(@Nullable Object target, @NonNull String key) {
        Object t = unwrap(target);
        if (t instanceof Scriptable) {
            Scriptable scriptable = (Scriptable) t;
            Integer index = parseIndex(key);
            return index == null ? scriptable.get(key, scriptable) : scriptable.get(index, scriptable);
        }
        if (t instanceof Map<?, ?>) return ((Map<?, ?>) t).get(key);
        Integer index = parseIndex(key);
        if (index != null) {
            if (t instanceof List<?>) {
                List<?> list = (List<?>) t;
                return index >= 0 && index < list.size() ? list.get(index) : null;
            }
            if (t != null && t.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(t);
                return index >= 0 && index < len ? java.lang.reflect.Array.get(t, index) : null;
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setDebugChild(@Nullable Object target, @NonNull String key, @Nullable Object value) throws Exception {
        Object t = unwrap(target);
        if (t instanceof Scriptable) {
            Scriptable scriptable = (Scriptable) t;
            Object jsValue = value;
            Context cx = Context.getCurrentContext();
            if (cx != null) jsValue = Context.javaToJS(value, scriptable);
            Integer index = parseIndex(key);
            if (index == null) scriptable.put(key, scriptable, jsValue);
            else scriptable.put(index, scriptable, jsValue);
            return;
        }
        if (t instanceof Map<?, ?>) {
            ((Map) t).put(key, value);
            return;
        }
        Integer index = parseIndex(key);
        if (index != null) {
            if (t instanceof List<?>) {
                List list = (List) t;
                if (index < 0 || index >= list.size()) throw new IndexOutOfBoundsException(key);
                list.set(index, value);
                return;
            }
            if (t != null && t.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(t);
                if (index < 0 || index >= len) throw new IndexOutOfBoundsException(key);
                java.lang.reflect.Array.set(t, index, value);
                return;
            }
        }
        throw new IllegalArgumentException("不支持修改变量: " + key);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyDebugObjectValue(@Nullable Object target, @Nullable Object jsonValue) throws Exception {
        Object t = unwrap(target);
        if (!(jsonValue instanceof JSONObject)) {
            throw new IllegalArgumentException("locals 必须是 JSON 对象");
        }
        JSONObject obj = (JSONObject) jsonValue;
        java.util.Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            setDebugChild(t, key, jsonToJsValue(obj.opt(key)));
        }
    }

    @Nullable
    private Integer parseIndex(@NonNull String key) {
        try {
            if (key.isEmpty()) return null;
            for (int i = 0; i < key.length(); i++) {
                if (!Character.isDigit(key.charAt(i))) return null;
            }
            return Integer.parseInt(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private Object jsonToJsValue(@Nullable Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            Object[] out = new Object[arr.length()];
            for (int i = 0; i < arr.length(); i++) out[i] = jsonToJsValue(arr.opt(i));
            Context cx = Context.getCurrentContext();
            if (cx != null && scope != null) {
                try { return cx.newArray(scope, out); } catch (Throwable ignored) { }
            }
            return out;
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            Context cx = Context.getCurrentContext();
            if (cx != null && scope != null) {
                Scriptable nativeObj = cx.newObject(scope);
                java.util.Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    nativeObj.put(key, nativeObj, jsonToJsValue(obj.opt(key)));
                }
                return nativeObj;
            }
            java.util.HashMap<String, Object> map = new java.util.HashMap<>();
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, jsonToJsValue(obj.opt(key)));
            }
            return map;
        }
        return value;
    }

    private static final class DebugCommand {
        final String command;
        final String expression;
        final JSONObject payload;

        DebugCommand(String command, String expression, JSONObject payload) {
            this.command = command;
            this.expression = expression;
            this.payload = payload == null ? new JSONObject() : payload;
        }
    }

    private Class<?> loadClass(String className) throws ClassNotFoundException {
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
        if (unwrapped == null || unwrapped == Scriptable.NOT_FOUND || unwrapped == Undefined.instance) return new Class<?>[0];

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

    private Object[] toObjectArray(Object value) {
        Object unwrapped = unwrap(value);
        if (unwrapped == null || unwrapped == Scriptable.NOT_FOUND || unwrapped == Undefined.instance) return new Object[0];
        if (unwrapped instanceof NativeArray) {
            NativeArray array = (NativeArray) unwrapped;
            Object[] result = new Object[(int) array.getLength()];
            for (int i = 0; i < result.length; i++) {
                result[i] = jsToJavaValue(array.get(i, array));
            }
            return result;
        }
        if (unwrapped instanceof Object[]) return (Object[]) unwrapped;
        if (unwrapped instanceof List<?>) return ((List<?>) unwrapped).toArray(new Object[0]);
        return new Object[]{unwrapped};
    }

    private Object unwrap(Object value) {
        if (value instanceof Wrapper) {
            return ((Wrapper) value).unwrap();
        }
        if (value == Context.getUndefinedValue() || value == Scriptable.NOT_FOUND || value == Undefined.instance) return null;
        return value;
    }

    @Nullable
    private Throwable toThrowable(Object value) {
        Object unwrapped = unwrap(value);
        if (unwrapped == null) return null;
        if (unwrapped instanceof Throwable) return (Throwable) unwrapped;
        return new RuntimeException(String.valueOf(unwrapped));
    }

    @Nullable
    private XposedInterface.ExceptionMode toExceptionMode(Object value) {
        Object unwrapped = unwrap(value);
        if (unwrapped == null) return null;
        if (unwrapped instanceof XposedInterface.ExceptionMode) return (XposedInterface.ExceptionMode) unwrapped;
        String name = String.valueOf(unwrapped).trim();
        if (name.startsWith("ExceptionMode.")) name = name.substring("ExceptionMode.".length());
        try {
            return XposedInterface.ExceptionMode.valueOf(name.toUpperCase());
        } catch (Throwable ignored) {
            log(Log.WARN, "未知 ExceptionMode: " + value);
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object enumValue(Class enumClass, String name) {
        try {
            return Enum.valueOf(enumClass, name);
        } catch (Throwable ignored) {
            return name;
        }
    }

    private int intConst(String name, int fallback) {
        try {
            Field field = XposedInterface.class.getField(name);
            field.setAccessible(true);
            return ((Number) field.get(null)).intValue();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private long longConst(String name, long fallback) {
        try {
            Field field = XposedInterface.class.getField(name);
            field.setAccessible(true);
            return ((Number) field.get(null)).longValue();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    @Nullable
    private Object invokeNoArg(@Nullable Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Method findCompatibleMethod(Class<?> clazz, String methodName, int argCount, boolean requireStatic) throws NoSuchMethodException {
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) continue;
            if (argCount >= 0 && method.getParameterTypes().length != argCount) continue;
            if (requireStatic && !Modifier.isStatic(method.getModifiers())) continue;
            method.setAccessible(true);
            return method;
        }
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null) return findCompatibleMethod(superClass, methodName, argCount, requireStatic);
        throw new NoSuchMethodException(clazz.getName() + "." + methodName + "/" + argCount);
    }

    private Constructor<?> findCompatibleConstructor(Class<?> clazz, int argCount) throws NoSuchMethodException {
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (argCount >= 0 && constructor.getParameterTypes().length != argCount) continue;
            constructor.setAccessible(true);
            return constructor;
        }
        throw new NoSuchMethodException(clazz.getName() + ".<init>/" + argCount);
    }
}
