package top.lovepikachu.XiaoHeiHook.dex;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;
import top.lovepikachu.XiaoHeiHook.HookEntry;

/**
 * dumpDex-style Java layer dumper.
 *
 * WrBug/dumpDex's low-SDK path hooks application/class loading and dumps the
 * Dex object reachable from Class.dexCache.getDex().getBytes(). This class keeps
 * the same timing idea for XiaoHeiHook: install early from the LSPosed entry,
 * then dump whenever the shell finally resolves a real business Class.
 */
public final class DexClassLoadDumper {
    private static final String TAG = "XHH-ClassDexDump";
    private static final Object INSTALL_LOCK = new Object();
    private static volatile boolean installed = false;
    private static volatile String currentPackageName = "";
    private static volatile String currentProcessName = "";
    private static volatile ClassLoader defaultLoader;
    private static volatile boolean debugLogging = false;

    private static final int DEFAULT_MAX_DEX_BYTES = 256 * 1024 * 1024;
    private static final int DEFAULT_MAX_DUMP_COUNT = 512;
    private static final CopyOnWriteArrayList<XposedInterface.HookHandle> HANDLES = new CopyOnWriteArrayList<>();
    private static final Map<String, Map<String, Object>> DUMPS = new ConcurrentHashMap<>();
    private static final LinkedHashSet<String> ATTEMPTED_CLASS_KEYS = new LinkedHashSet<>();
    private static final LinkedHashSet<Integer> COOKIE_FALLBACK_LOADERS = new LinkedHashSet<>();
    private static final CopyOnWriteArrayList<ClassLoader> APP_CLASS_LOADERS = new CopyOnWriteArrayList<>();
    private static final Object ATTEMPT_LOCK = new Object();
    private static volatile int attemptedClasses = 0;
    private static volatile int skippedClasses = 0;
    private static volatile int failedClasses = 0;

    private DexClassLoadDumper() {}

    public static void install(@Nullable HookEntry module,
                               @Nullable String packageName,
                               @Nullable String processName,
                               @Nullable ClassLoader loader) {
        install(module, packageName, processName, loader, false);
    }

    public static void install(@Nullable HookEntry module,
                               @Nullable String packageName,
                               @Nullable String processName,
                               @Nullable ClassLoader loader,
                               boolean enableDebugLogging) {
        currentPackageName = packageName == null ? "" : packageName;
        currentProcessName = processName == null ? "" : processName;
        debugLogging = debugLogging || enableDebugLogging;
        if (loader != null) defaultLoader = loader;
        if (module == null) return;
        if (installed) return;
        synchronized (INSTALL_LOCK) {
            if (installed) return;
            installed = true;
            hookInstrumentationNewApplication(module);
            hookApplicationAttach(module);
            hookComAndroidDexCreate(module);
            hookClassLoaderLoadClass(module);
            log(module, Log.INFO, "class-load dex dumper installed package=" + currentPackageName
                    + " process=" + currentProcessName
                    + " sdk=" + Build.VERSION.SDK_INT
                    + " handles=" + HANDLES.size(), null);
        }
    }

    @NonNull
    public static String outputDir() {
        String pkg = currentPackageName == null || currentPackageName.trim().isEmpty() ? "unknown" : currentPackageName.trim();
        return "/data/user/0/" + pkg + "/code_cache/xhh_class_dex";
    }

    @NonNull
    private static String cookieFallbackOutputDir() {
        String pkg = currentPackageName == null || currentPackageName.trim().isEmpty() ? "unknown" : currentPackageName.trim();
        return "/data/user/0/" + pkg + "/code_cache/xhh_dumpdex";
    }

    @NonNull
    public static List<Map<String, Object>> sources() {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> value : DUMPS.values()) {
            if (value != null) out.add(new LinkedHashMap<>(value));
        }
        Collections.sort(out, (a, b) -> String.valueOf(a.get("path")).compareTo(String.valueOf(b.get("path"))));
        return out;
    }

    @NonNull
    public static Map<String, Object> status() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("installed", installed);
        out.put("packageName", currentPackageName);
        out.put("processName", currentProcessName);
        out.put("outputDir", outputDir());
        out.put("cookieFallbackDir", cookieFallbackOutputDir());
        out.put("hookCount", HANDLES.size());
        out.put("attemptedClasses", attemptedClasses);
        out.put("skippedClasses", skippedClasses);
        out.put("failedClasses", failedClasses);
        out.put("dumpCount", DUMPS.size());
        out.put("appClassLoaderCount", APP_CLASS_LOADERS.size());
        ArrayList<Map<String, Object>> loaderRows = new ArrayList<>();
        for (ClassLoader loader : APP_CLASS_LOADERS) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("id", loader == null ? null : System.identityHashCode(loader));
            row.put("loader", String.valueOf(loader));
            row.put("class", loader == null ? null : loader.getClass().getName());
            loaderRows.add(row);
        }
        out.put("appClassLoaders", loaderRows);
        out.put("sources", sources());
        return out;
    }

    public static void rememberAppClassLoader(@Nullable ClassLoader loader, @NonNull String origin, @Nullable HookEntry module) {
        if (loader == null) return;
        for (ClassLoader existing : APP_CLASS_LOADERS) {
            if (existing == loader) return;
        }
        APP_CLASS_LOADERS.add(loader);
        defaultLoader = loader;
        log(module, Log.INFO, "remember app ClassLoader origin=" + origin
                + " id=" + System.identityHashCode(loader)
                + " loader=" + loader
                + " class=" + loader.getClass().getName(), null);
    }

    @NonNull
    public static List<ClassLoader> appClassLoaders() {
        return new ArrayList<>(APP_CLASS_LOADERS);
    }

    private static void hookInstrumentationNewApplication(@NonNull HookEntry module) {
        try {
            Method m = Instrumentation.class.getDeclaredMethod("newApplication", ClassLoader.class, String.class, Context.class);
            m.setAccessible(true);
            XposedInterface.HookHandle handle = module.hook(m).intercept(chain -> {
                try {
                    List<?> args = chain.getArgs();
                    if (args != null && !args.isEmpty() && args.get(0) instanceof ClassLoader) {
                        rememberAppClassLoader((ClassLoader) args.get(0), "Instrumentation.newApplication.arg", module);
                    }
                } catch (Throwable ignored) {}
                Object result = chain.proceed();
                if (result instanceof Application) {
                    try { rememberAppClassLoader(classLoaderFromContext((Application) result), "Instrumentation.newApplication.result", module); } catch (Throwable ignored) {}
                    dumpClassDex(((Application) result).getClass(), "Instrumentation.newApplication", module);
                }
                return result;
            });
            HANDLES.add(handle);
            log(module, Log.INFO, "hooked Instrumentation.newApplication", null);
        } catch (Throwable t) {
            log(module, Log.DEBUG, "skip hook Instrumentation.newApplication: " + t, null);
        }
    }

    private static void hookApplicationAttach(@NonNull HookEntry module) {
        try {
            Method m = Application.class.getDeclaredMethod("attach", Context.class);
            m.setAccessible(true);
            XposedInterface.HookHandle handle = module.hook(m).intercept(chain -> {
                Context context = null;
                try {
                    List<?> args = chain.getArgs();
                    if (args != null && !args.isEmpty() && args.get(0) instanceof Context) context = (Context) args.get(0);
                    if (context != null) rememberAppClassLoader(classLoaderFromContext(context), "Application.attach.arg.before", module);
                } catch (Throwable ignored) {}
                Object result = chain.proceed();
                try { if (context != null) rememberAppClassLoader(classLoaderFromContext(context), "Application.attach.arg.after", module); } catch (Throwable ignored) {}
                Object self = null;
                try { self = chain.getThisObject(); } catch (Throwable ignored) {}
                if (self instanceof Application) {
                    try { rememberAppClassLoader(classLoaderFromContext((Application) self), "Application.attach.self", module); } catch (Throwable ignored) {}
                    dumpClassDex(((Application) self).getClass(), "Application.attach", module);
                }
                return result;
            });
            HANDLES.add(handle);
            log(module, Log.INFO, "hooked Application.attach", null);
        } catch (Throwable t) {
            log(module, Log.DEBUG, "skip hook Application.attach: " + t, null);
        }
    }


    /**
     * aos3618/DumpDex-style hook: many packers create com.android.dex.Dex from a ByteBuffer
     * after native unpacking. Dumping here catches the transient, already-decrypted buffer
     * before it is consumed by the shell.
     */
    private static void hookComAndroidDexCreate(@NonNull HookEntry module) {
        try {
            Class<?> dexClass = Class.forName("com.android.dex.Dex", false, ClassLoader.getSystemClassLoader());
            Method m = dexClass.getDeclaredMethod("create", ByteBuffer.class);
            m.setAccessible(true);
            XposedInterface.HookHandle handle = module.hook(m).intercept(chain -> {
                try {
                    List<?> args = chain.getArgs();
                    if (args != null && !args.isEmpty() && args.get(0) instanceof ByteBuffer) {
                        dumpByteBuffer((ByteBuffer) args.get(0), "com.android.dex.Dex.create(ByteBuffer)", module);
                    }
                } catch (Throwable t) {
                    log(module, Log.WARN, "Dex.create ByteBuffer dump failed", t);
                }
                return chain.proceed();
            });
            HANDLES.add(handle);
            log(module, Log.INFO, "hooked com.android.dex.Dex.create(ByteBuffer)", null);
        } catch (Throwable t) {
            log(module, Log.DEBUG, "skip hook com.android.dex.Dex.create(ByteBuffer): " + t, null);
        }
    }

    private static void hookClassLoaderLoadClass(@NonNull HookEntry module) {
        try {
            for (Method m : ClassLoader.class.getDeclaredMethods()) {
                if (!"loadClass".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (!(p.length == 1 && p[0] == String.class) && !(p.length == 2 && p[0] == String.class && p[1] == boolean.class)) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    XposedInterface.HookHandle handle = module.hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof Class<?>) {
                            dumpClassDex((Class<?>) result, "ClassLoader.loadClass" + describeTypes(m.getParameterTypes()), module);
                        }
                        return result;
                    });
                    HANDLES.add(handle);
                    log(module, Log.INFO, "hooked ClassLoader." + m, null);
                } catch (Throwable t) {
                    log(module, Log.DEBUG, "skip ClassLoader method " + m + ": " + t, null);
                }
            }
        } catch (Throwable t) {
            log(module, Log.DEBUG, "skip hook ClassLoader.loadClass: " + t, null);
        }
    }

    @Nullable
    public static String dumpClassDex(@Nullable Class<?> clazz, @NonNull String origin, @Nullable HookEntry module) {
        if (clazz == null) return null;
        attemptedClasses++;
        String name = clazz.getName();
        if (!shouldProbeClass(name)) {
            skippedClasses++;
            return null;
        }
        String key = System.identityHashCode(clazz.getClassLoader()) + ":" + name;
        synchronized (ATTEMPT_LOCK) {
            if (ATTEMPTED_CLASS_KEYS.contains(key)) return null;
            if (ATTEMPTED_CLASS_KEYS.size() > 20000) ATTEMPTED_CLASS_KEYS.clear();
            ATTEMPTED_CLASS_KEYS.add(key);
        }
        try {
            byte[] bytes = dexBytesFromClass(clazz);
            if (bytes == null || bytes.length < 0x70 || !looksLikeDex(bytes)) {
                tryCookieFallback(clazz, origin, module);
                return null;
            }
            String path = dumpDexBytes(bytes, origin, "Class.dexCache", module, clazz);
            if (path != null) {
                ClassLoader loader = clazz.getClassLoader();
                if (loader != null) {
                    DexRuntimeRegistry.registerPath(loader, path, "class-dex-cache:" + origin);
                } else if (defaultLoader != null) {
                    DexRuntimeRegistry.registerPath(defaultLoader, path, "class-dex-cache:" + origin);
                }
            }
            return path;
        } catch (Throwable t) {
            failedClasses++;
            log(module, Log.DEBUG, "dump class dex failed class=" + name + " origin=" + origin + " err=" + t, null);
            return null;
        }
    }


    private static void tryCookieFallback(@NonNull Class<?> clazz, @NonNull String origin, @Nullable HookEntry module) {
        ClassLoader loader = clazz.getClassLoader();
        if (loader == null) return;
        int id = System.identityHashCode(loader);
        synchronized (ATTEMPT_LOCK) {
            if (COOKIE_FALLBACK_LOADERS.contains(id)) return;
            if (COOKIE_FALLBACK_LOADERS.size() > 512) COOKIE_FALLBACK_LOADERS.clear();
            COOKIE_FALLBACK_LOADERS.add(id);
        }
        try {
            DexCookieDumper.Result result = DexCookieDumper.dump(loader, cookieFallbackOutputDir(), DEFAULT_MAX_DEX_BYTES, 64, false, false, false);
            for (Map<String, Object> map : result.dumps) {
                Object path = map == null ? null : map.get("path");
                if (path != null) DexRuntimeRegistry.registerPath(loader, String.valueOf(path), "class-load-cookie-fallback:" + origin);
            }
            if (!result.dumps.isEmpty()) {
                log(module, Log.INFO, "cookie fallback dumped count=" + result.dumps.size() + " class=" + clazz.getName() + " origin=" + origin, null);
            }
        } catch (Throwable t) {
            log(module, Log.DEBUG, "cookie fallback failed class=" + clazz.getName() + " err=" + t, null);
        }
    }

    @Nullable
    private static ClassLoader classLoaderFromContext(@Nullable Object context) {
        if (context == null) return null;
        try {
            Object value = callNoArg(context, "getClassLoader");
            if (value instanceof ClassLoader) return (ClassLoader) value;
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private static byte[] dexBytesFromClass(@NonNull Class<?> clazz) throws Exception {
        Object dexCache = readField(clazz, "dexCache");
        if (dexCache == null) return null;
        Object dex = callNoArg(dexCache, "getDex");
        if (dex == null) {
            dex = readField(dexCache, "dex");
            if (dex == null) dex = readField(dexCache, "mDex");
        }
        if (dex == null) return null;
        Object bytes = callNoArg(dex, "getBytes");
        if (bytes instanceof byte[]) return (byte[]) bytes;
        bytes = readField(dex, "bytes");
        if (bytes instanceof byte[]) return (byte[]) bytes;
        return null;
    }


    @Nullable
    private static String dumpByteBuffer(@Nullable ByteBuffer buffer, @NonNull String origin, @Nullable HookEntry module) {
        if (buffer == null) return null;
        try {
            ByteBuffer dup = buffer.duplicate();
            int remaining = dup.remaining();
            if (remaining < 0x70) {
                dup.position(0);
                remaining = dup.remaining();
            }
            if (remaining < 0x70 || remaining > DEFAULT_MAX_DEX_BYTES) return null;
            byte[] bytes = new byte[remaining];
            dup.get(bytes);
            return dumpDexBytes(bytes, origin, "Dex.create", module, null);
        } catch (Throwable t) {
            log(module, Log.WARN, "dump ByteBuffer failed origin=" + origin, t);
            return null;
        }
    }

    @Nullable
    private static String dumpDexBytes(@Nullable byte[] bytes, @NonNull String origin, @NonNull String sourceKind, @Nullable HookEntry module, @Nullable Class<?> clazz) throws Exception {
        if (bytes == null || bytes.length < 0x70) return null;
        if (!looksLikeDex(bytes)) return null;
        if (bytes.length > DEFAULT_MAX_DEX_BYTES) {
            log(module, Log.WARN, "skip dex too large origin=" + origin + " size=" + bytes.length, null);
            return null;
        }
        if (DUMPS.size() >= DEFAULT_MAX_DUMP_COUNT) return null;
        String sha = sha256(bytes);
        if (DUMPS.containsKey(sha)) return String.valueOf(DUMPS.get(sha).get("path"));
        File dir = new File(outputDir());
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdir failed: " + dir.getAbsolutePath());
        String name = clazz == null ? sourceKind : clazz.getName();
        String safeName = name.replaceAll("[^A-Za-z0-9_.-]+", "_");
        if (safeName.length() > 96) safeName = safeName.substring(0, 96);
        File out = new File(dir, "classdex_" + DUMPS.size() + "_" + bytes.length + "_" + sha.substring(0, 12) + "_" + safeName + ".dex");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(bytes);
            fos.flush();
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("path", out.getAbsolutePath());
        map.put("fileSize", bytes.length);
        map.put("sha256", sha);
        map.put("origin", origin);
        map.put("sourceKind", sourceKind);
        if (clazz != null) {
            map.put("className", clazz.getName());
            map.put("loader", String.valueOf(clazz.getClassLoader()));
        }
        DUMPS.put(sha, map);
        log(module, Log.INFO, "dumped class-load dex path=" + out.getAbsolutePath()
                + " size=" + bytes.length
                + " origin=" + origin
                + (clazz == null ? "" : " class=" + clazz.getName()), null);
        return out.getAbsolutePath();
    }

    @Nullable
    private static Object readField(@Nullable Object target, @NonNull String name) throws Exception {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    @Nullable
    private static Object callNoArg(@Nullable Object target, @NonNull String name) throws Exception {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static boolean shouldProbeClass(@Nullable String name) {
        if (name == null || name.length() == 0) return false;
        if (name.startsWith("java.") || name.startsWith("javax.")) return false;
        if (name.startsWith("android.") || name.startsWith("androidx.")) return false;
        if (name.startsWith("kotlin.")) return false;
        if (name.startsWith("dalvik.")) return false;
        if (name.startsWith("com.android.")) return false;
        if (name.startsWith("org.mozilla.javascript.")) return false;
        if (name.startsWith("io.github.libxposed.")) return false;
        if (name.startsWith("top.lovepikachu.XiaoHeiHook.")) return false;
        return true;
    }

    private static boolean looksLikeDex(byte[] bytes) {
        return bytes != null && bytes.length >= 8 && bytes[0] == 'd' && bytes[1] == 'e' && bytes[2] == 'x' && bytes[3] == '\n';
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static String describeTypes(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(types[i] == null ? "null" : types[i].getName());
        }
        return sb.append(')').toString();
    }

    private static void log(@Nullable HookEntry module, int priority, @NonNull String message, @Nullable Throwable tr) {
        if (!debugLogging && priority < Log.WARN) return;
        Log.println(priority, TAG, message + (tr == null ? "" : " : " + tr));
        try {
            if (module != null) {
                if (tr == null) module.log(priority, TAG, message);
                else module.log(priority, TAG, message, tr);
            }
        } catch (Throwable ignored) {
        }
    }
}
