package top.lovepikachu.XiaoHeiHook.dex;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
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
 * Process-local registry for dex sources that are not visible from the initial app ClassLoader.
 *
 * Many protected apps expose only a tiny stub dex through the package PathClassLoader. The real
 * business dex is loaded later through DexClassLoader/BaseDexClassLoader.addDexPath or, on newer
 * Android versions, InMemoryDexClassLoader. This registry captures those runtime sources so the
 * JS `dex` API can scan them via dex.fromRuntime().
 */
public final class DexRuntimeRegistry {
    private static final String TAG = "XHH-DexRuntime";
    private static final int MAX_IN_MEMORY_DEX_BYTES = 80 * 1024 * 1024;

    private static final Object INSTALL_LOCK = new Object();
    private static volatile boolean hooksInstalled = false;
    private static volatile String currentPackageName = "";
    private static volatile String currentProcessName = "";
    private static volatile boolean debugLogging = false;

    private static final Map<Integer, LoaderRecord> LOADERS = new ConcurrentHashMap<>();
    private static final Map<String, DexApiFacade.DexSource> SOURCES = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<XposedInterface.HookHandle> HOOK_HANDLES = new CopyOnWriteArrayList<>();

    private DexRuntimeRegistry() {}

    public static void setDebugLogging(boolean enabled) {
        debugLogging = debugLogging || enabled;
    }

    public static boolean isDebugLoggingEnabled() {
        return debugLogging;
    }

    public static void install(@Nullable HookEntry module,
                               @Nullable String packageName,
                               @Nullable String processName,
                               @Nullable ClassLoader defaultLoader) {
        currentPackageName = packageName == null ? "" : packageName;
        currentProcessName = processName == null ? "" : processName;
        if (defaultLoader != null) {
            registerLoader(defaultLoader, "install-default-loader", DexApiFacade.DexSourceResolver.fromLoader(defaultLoader));
        }
        if (module == null) return;
        if (hooksInstalled) return;
        synchronized (INSTALL_LOCK) {
            if (hooksInstalled) return;
            hooksInstalled = true;
            hookPathConstructors(module);
            hookBaseDexClassLoaderAddDexPath(module);
            hookInMemoryDexClassLoader(module);
            hookDexFileApis(module);
            hookDexPathListFactories(module);
            log(module, Log.INFO, "runtime dex capture installed package=" + currentPackageName + " process=" + currentProcessName + " handles=" + HOOK_HANDLES.size(), null);
        }
    }

    public static List<DexApiFacade.DexSource> allSources() {
        return new ArrayList<>(new LinkedHashSet<>(SOURCES.values()));
    }

    public static List<DexApiFacade.DexSource> sourcesForLoader(@Nullable ClassLoader loader) {
        if (loader == null) return Collections.emptyList();
        LinkedHashSet<DexApiFacade.DexSource> out = new LinkedHashSet<>();
        int id = System.identityHashCode(loader);
        for (DexApiFacade.DexSource source : SOURCES.values()) {
            if (source.loader == loader || System.identityHashCode(source.loader) == id) out.add(source);
        }
        out.addAll(DexApiFacade.DexSourceResolver.fromLoader(loader));
        return new ArrayList<>(out);
    }

    public static List<Map<String, Object>> loaders() {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (LoaderRecord record : LOADERS.values()) {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("loaderId", record.loaderId);
            map.put("loader", record.loaderText);
            map.put("className", record.className);
            map.put("origin", record.origin);
            map.put("sourceCount", record.sourceKeys.size());
            map.put("createdAt", record.createdAt);
            out.add(map);
        }
        return out;
    }

    static void registerLoader(@Nullable ClassLoader loader, @NonNull String origin, @Nullable List<DexApiFacade.DexSource> sources) {
        if (loader == null) return;
        LoaderRecord record = ensureLoaderRecord(loader, origin);
        if (sources == null) return;
        for (DexApiFacade.DexSource source : sources) {
            if (source == null) continue;
            registerSource(loader, source.path, source.entry, source.type, origin + ":" + source.origin, record);
        }
    }

    public static boolean registerPath(@Nullable ClassLoader loader, @Nullable String rawPath, @NonNull String origin) {
        if (loader == null || rawPath == null) return false;
        boolean any = false;
        for (String path : splitDexPathList(rawPath)) {
            if (path == null || path.trim().isEmpty()) continue;
            File file = new File(path.trim());
            if (!file.isFile()) continue;
            LoaderRecord record = ensureLoaderRecord(loader, origin);
            if (registerSource(loader, file.getAbsolutePath(), null, guessType(file), origin, record)) any = true;
        }
        // Also re-read pathList after loader mutation. Some shells patch dexElements directly.
        registerLoader(loader, origin + ":reflection-refresh", DexApiFacade.DexSourceResolver.fromLoader(loader));
        return any;
    }

    private static LoaderRecord ensureLoaderRecord(@NonNull ClassLoader loader, @NonNull String origin) {
        int id = System.identityHashCode(loader);
        LoaderRecord existing = LOADERS.get(id);
        if (existing != null) return existing;
        LoaderRecord record = new LoaderRecord(id, loader.getClass().getName(), String.valueOf(loader), origin, System.currentTimeMillis());
        LoaderRecord raced = LOADERS.putIfAbsent(id, record);
        return raced == null ? record : raced;
    }

    private static boolean registerSource(@Nullable ClassLoader loader,
                                          @Nullable String path,
                                          @Nullable String entry,
                                          @Nullable String type,
                                          @NonNull String origin,
                                          @Nullable LoaderRecord record) {
        if (path == null || path.trim().isEmpty()) return false;
        File file = new File(path.trim());
        if (!file.isFile()) return false;
        String cleanPath = file.getAbsolutePath();
        String cleanType = type == null || type.trim().isEmpty() ? guessType(file) : type;
        int loaderId = loader == null ? 0 : System.identityHashCode(loader);
        String key = loaderId + "|" + cleanPath + "|" + String.valueOf(entry);
        DexApiFacade.DexSource source = new DexApiFacade.DexSource(cleanPath, entry, cleanType, loader, origin);
        DexApiFacade.DexSource previous = SOURCES.putIfAbsent(key, source);
        if (record != null) record.sourceKeys.add(key);
        if (previous == null) {
            if (debugLogging) Log.i(TAG, "registered runtime dex source loaderId=" + loaderId + " type=" + cleanType + " origin=" + origin + " path=" + cleanPath + (entry == null ? "" : "!" + entry));
            return true;
        }
        return false;
    }



    private static void hookDexFileApis(@NonNull HookEntry module) {
        try {
            Class<?> dexFileClass = Class.forName("dalvik.system.DexFile");

            for (Constructor<?> constructor : dexFileClass.getDeclaredConstructors()) {
                if (!hasParameterAssignableTo(constructor.getParameterTypes(), String.class)) continue;
                try {
                    constructor.setAccessible(true);
                    XposedInterface.HookHandle handle = module.hook(constructor).intercept(chain -> {
                        Object result = chain.proceed();
                        registerSourcesFromChainArgs(chain, null, "DexFile.<init>" + constructor);
                        return result;
                    });
                    HOOK_HANDLES.add(handle);
                    log(module, Log.INFO, "hooked DexFile constructor " + constructor, null);
                } catch (Throwable t) {
                    log(module, Log.DEBUG, "skip DexFile constructor hook " + constructor + ": " + t, null);
                }
            }

            for (Method method : dexFileClass.getDeclaredMethods()) {
                String name = method.getName();
                if (!("loadDex".equals(name)
                        || name.contains("openDexFile")
                        || name.contains("openInMemoryDexFile")
                        || name.contains("defineClass"))) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedInterface.HookHandle handle = module.hook(method).intercept(chain -> {
                        ClassLoader loader = findClassLoaderInArgs(chain);
                        ArrayList<String> dumpedPaths = new ArrayList<>();
                        dumpByteBuffersFromChainArgs(chain, dumpedPaths);
                        Object result = chain.proceed();
                        registerSourcesFromChainArgs(chain, loader, "DexFile." + method.getName() + method);
                        LoaderRecord record = loader == null ? null : ensureLoaderRecord(loader, "DexFile." + method.getName());
                        for (String path : dumpedPaths) {
                            registerSource(loader, path, null, "dex", "DexFile." + method.getName() + ":ByteBuffer.dump", record);
                        }
                        return result;
                    });
                    HOOK_HANDLES.add(handle);
                    log(module, Log.INFO, "hooked DexFile method " + method, null);
                } catch (Throwable t) {
                    log(module, Log.DEBUG, "skip DexFile method hook " + method + ": " + t, null);
                }
            }
        } catch (Throwable t) {
            log(module, Log.DEBUG, "skip hook DexFile APIs: " + t, null);
        }
    }

    private static void hookDexPathListFactories(@NonNull HookEntry module) {
        try {
            Class<?> cls = Class.forName("dalvik.system.DexPathList");
            for (Method method : cls.getDeclaredMethods()) {
                String name = method.getName();
                if (!(name.contains("makeDexElements") || name.contains("makePathElements") || name.contains("loadDexFile"))) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedInterface.HookHandle handle = module.hook(method).intercept(chain -> {
                        registerFileLikeArgs(chain, null, "DexPathList." + method.getName() + method + ":before");
                        Object result = chain.proceed();
                        registerFileLikeArgs(chain, null, "DexPathList." + method.getName() + method + ":after");
                        return result;
                    });
                    HOOK_HANDLES.add(handle);
                    log(module, Log.INFO, "hooked DexPathList method " + method, null);
                } catch (Throwable t) {
                    log(module, Log.DEBUG, "skip DexPathList method hook " + method + ": " + t, null);
                }
            }
        } catch (Throwable t) {
            log(module, Log.DEBUG, "skip hook DexPathList factories: " + t, null);
        }
    }

    private static void registerSourcesFromChainArgs(@NonNull XposedInterface.Chain chain,
                                                     @Nullable ClassLoader fallbackLoader,
                                                     @NonNull String origin) {
        ClassLoader loader = fallbackLoader == null ? findClassLoaderInArgs(chain) : fallbackLoader;
        registerFileLikeArgs(chain, loader, origin);
    }

    private static void registerFileLikeArgs(@NonNull XposedInterface.Chain chain,
                                             @Nullable ClassLoader loader,
                                             @NonNull String origin) {
        try {
            List<?> args = chain.getArgs();
            if (args == null) return;
            for (Object arg : args) {
                registerFileLikeObject(loader, arg, origin, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void registerFileLikeObject(@Nullable ClassLoader loader,
                                               @Nullable Object value,
                                               @NonNull String origin,
                                               int depth) {
        if (value == null || depth > 3) return;
        if (value instanceof String) {
            registerAnyReadableDexOrZip(loader, (String) value, origin);
            return;
        }
        if (value instanceof File) {
            registerAnyReadableDexOrZip(loader, ((File) value).getAbsolutePath(), origin);
            return;
        }
        Class<?> cls = value.getClass();
        if (cls.isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < len; i++) {
                registerFileLikeObject(loader, java.lang.reflect.Array.get(value, i), origin, depth + 1);
            }
            return;
        }
        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                registerFileLikeObject(loader, item, origin, depth + 1);
            }
        }
    }

    private static boolean registerAnyReadableDexOrZip(@Nullable ClassLoader loader,
                                                       @Nullable String rawPath,
                                                       @NonNull String origin) {
        if (rawPath == null) return false;
        boolean any = false;
        for (String path : splitDexPathList(rawPath)) {
            File file = new File(path.trim());
            if (!file.isFile()) continue;
            if (!looksLikeDexFile(file) && !looksLikeZipFile(file) && !looksLikeDexSourceName(file)) continue;
            LoaderRecord record = loader == null ? null : ensureLoaderRecord(loader, origin);
            if (registerSource(loader, file.getAbsolutePath(), null, guessType(file), origin, record)) any = true;
        }
        return any;
    }

    private static boolean looksLikeDexSourceName(@NonNull File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".dex") || name.endsWith(".apk") || name.endsWith(".jar") || name.endsWith(".zip");
    }

    @Nullable
    private static ClassLoader findClassLoaderInArgs(@NonNull XposedInterface.Chain chain) {
        try {
            List<?> args = chain.getArgs();
            if (args == null) return null;
            for (Object arg : args) {
                if (arg instanceof ClassLoader) return (ClassLoader) arg;
            }
        } catch (Throwable ignored) {
        }
        Object self = null;
        try { self = chain.getThisObject(); } catch (Throwable ignored) {}
        if (self instanceof ClassLoader) return (ClassLoader) self;
        return null;
    }

    private static void dumpByteBuffersFromChainArgs(@NonNull XposedInterface.Chain chain, @NonNull ArrayList<String> dumpedPaths) {
        try {
            List<?> args = chain.getArgs();
            if (args == null) return;
            for (Object arg : args) dumpByteBufferArgument(arg, dumpedPaths);
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasParameterAssignableTo(@NonNull Class<?>[] parameterTypes, @NonNull Class<?> target) {
        for (Class<?> type : parameterTypes) {
            if (target.isAssignableFrom(type)) return true;
        }
        return false;
    }

    private static void hookPathConstructors(@NonNull HookEntry module) {
        hookStringPathConstructor(module, "dalvik.system.DexClassLoader", new Class<?>[]{String.class, String.class, String.class, ClassLoader.class});
        hookStringPathConstructor(module, "dalvik.system.PathClassLoader", new Class<?>[]{String.class, ClassLoader.class});
        hookStringPathConstructor(module, "dalvik.system.PathClassLoader", new Class<?>[]{String.class, String.class, ClassLoader.class});
        hookStringPathConstructor(module, "dalvik.system.DelegateLastClassLoader", new Class<?>[]{String.class, ClassLoader.class});
        hookStringPathConstructor(module, "dalvik.system.DelegateLastClassLoader", new Class<?>[]{String.class, String.class, ClassLoader.class});
    }

    private static void hookStringPathConstructor(@NonNull HookEntry module, @NonNull String className, @NonNull Class<?>[] parameterTypes) {
        try {
            Class<?> cls = Class.forName(className);
            Constructor<?> constructor = cls.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            XposedInterface.HookHandle handle = module.hook(constructor).intercept(chain -> {
                String dexPath = safeArgString(chain, 0);
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (!(self instanceof ClassLoader) && result instanceof ClassLoader) self = result;
                if (self instanceof ClassLoader) {
                    ClassLoader loader = (ClassLoader) self;
                    registerPath(loader, dexPath, className + ".<init>");
                    registerLoader(loader, className + ".<init>:reflection", DexApiFacade.DexSourceResolver.fromLoader(loader));
                }
                return result;
            });
            HOOK_HANDLES.add(handle);
            log(module, Log.INFO, "hooked " + className + constructor, null);
        } catch (Throwable t) {
            log(module, Log.DEBUG, "skip hook constructor " + className + " " + describeTypes(parameterTypes) + ": " + t, null);
        }
    }

    private static void hookBaseDexClassLoaderAddDexPath(@NonNull HookEntry module) {
        try {
            Class<?> cls = Class.forName("dalvik.system.BaseDexClassLoader");
            Method[] methods = cls.getDeclaredMethods();
            for (Method method : methods) {
                if (!"addDexPath".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length < 1 || params[0] != String.class) continue;
                method.setAccessible(true);
                XposedInterface.HookHandle handle = module.hook(method).intercept(chain -> {
                    String dexPath = safeArgString(chain, 0);
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof ClassLoader) {
                        ClassLoader loader = (ClassLoader) self;
                        registerPath(loader, dexPath, "BaseDexClassLoader.addDexPath");
                        registerLoader(loader, "BaseDexClassLoader.addDexPath:reflection", DexApiFacade.DexSourceResolver.fromLoader(loader));
                    }
                    return result;
                });
                HOOK_HANDLES.add(handle);
                log(module, Log.INFO, "hooked BaseDexClassLoader." + method, null);
            }
        } catch (Throwable t) {
            log(module, Log.DEBUG, "skip hook BaseDexClassLoader.addDexPath: " + t, null);
        }
    }

    private static void hookInMemoryDexClassLoader(@NonNull HookEntry module) {
        tryHookInMemoryConstructor(module, new Class<?>[]{ByteBuffer.class, ClassLoader.class});
        tryHookInMemoryConstructor(module, new Class<?>[]{ByteBuffer[].class, ClassLoader.class});
    }

    private static void tryHookInMemoryConstructor(@NonNull HookEntry module, @NonNull Class<?>[] parameterTypes) {
        try {
            Class<?> cls = Class.forName("dalvik.system.InMemoryDexClassLoader");
            Constructor<?> constructor = cls.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            XposedInterface.HookHandle handle = module.hook(constructor).intercept(chain -> {
                ArrayList<String> dumpedPaths = new ArrayList<>();
                Object arg0 = null;
                try { arg0 = chain.getArg(0); } catch (Throwable ignored) {}
                dumpByteBufferArgument(arg0, dumpedPaths);
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (!(self instanceof ClassLoader) && result instanceof ClassLoader) self = result;
                if (self instanceof ClassLoader) {
                    ClassLoader loader = (ClassLoader) self;
                    LoaderRecord record = ensureLoaderRecord(loader, "InMemoryDexClassLoader.<init>");
                    for (String path : dumpedPaths) {
                        registerSource(loader, path, null, "dex", "InMemoryDexClassLoader.dump", record);
                    }
                    registerLoader(loader, "InMemoryDexClassLoader.<init>:reflection", DexApiFacade.DexSourceResolver.fromLoader(loader));
                }
                return result;
            });
            HOOK_HANDLES.add(handle);
            log(module, Log.INFO, "hooked InMemoryDexClassLoader" + describeTypes(parameterTypes), null);
        } catch (Throwable t) {
            log(module, Log.DEBUG, "skip hook InMemoryDexClassLoader" + describeTypes(parameterTypes) + ": " + t, null);
        }
    }

    private static void dumpByteBufferArgument(@Nullable Object value, @NonNull ArrayList<String> dumpedPaths) {
        if (value instanceof ByteBuffer) {
            String path = dumpByteBuffer((ByteBuffer) value);
            if (path != null) dumpedPaths.add(path);
            return;
        }
        if (value instanceof ByteBuffer[]) {
            ByteBuffer[] buffers = (ByteBuffer[]) value;
            for (ByteBuffer buffer : buffers) {
                String path = dumpByteBuffer(buffer);
                if (path != null) dumpedPaths.add(path);
            }
        }
    }

    @Nullable
    private static String dumpByteBuffer(@Nullable ByteBuffer buffer) {
        if (buffer == null) return null;
        try {
            ByteBuffer dup = buffer.duplicate();
            int remaining = dup.remaining();
            if (remaining <= 0 && dup.capacity() > 0) {
                dup.clear();
                remaining = dup.remaining();
            }
            if (remaining <= 0 || remaining > MAX_IN_MEMORY_DEX_BYTES) return null;
            byte[] bytes = new byte[remaining];
            dup.get(bytes);
            if (!looksLikeDexBytes(bytes)) return null;
            File dir = runtimeDumpDir();
            if (!dir.exists() && !dir.mkdirs()) return null;
            String name = "inmemory_" + System.currentTimeMillis() + "_" + shortSha256(bytes) + ".dex";
            File out = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
                fos.flush();
            }
            if (debugLogging) Log.i(TAG, "dumped InMemoryDexClassLoader buffer to " + out.getAbsolutePath() + " size=" + bytes.length);
            return out.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "dump in-memory dex failed", t);
            return null;
        }
    }

    private static File runtimeDumpDir() {
        if (currentPackageName != null && currentPackageName.length() > 0) {
            File appDir = new File("/data/user/0/" + currentPackageName + "/code_cache/xhh_runtime_dex");
            File parent = appDir.getParentFile();
            if (parent != null && (parent.exists() || parent.mkdirs())) return appDir;
        }
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && tmp.length() > 0) return new File(tmp, "xhh_runtime_dex");
        return new File(".", "xhh_runtime_dex");
    }

    private static boolean looksLikeDexBytes(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && bytes[0] == 'd' && bytes[1] == 'e' && bytes[2] == 'x' && bytes[3] == '\n';
    }

    private static String shortSha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(6, digest.length); i++) sb.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(bytes.length);
        }
    }

    private static List<String> splitDexPathList(@NonNull String rawPath) {
        ArrayList<String> out = new ArrayList<>();
        String text = rawPath.trim();
        if (text.isEmpty()) return out;
        char sep = File.pathSeparatorChar;
        int start = 0;
        while (start <= text.length()) {
            int idx = text.indexOf(sep, start);
            if (idx < 0) idx = text.length();
            String item = text.substring(start, idx).trim();
            if (!item.isEmpty()) out.add(item);
            if (idx >= text.length()) break;
            start = idx + 1;
        }
        return out;
    }

    private static String guessType(@NonNull File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".dex")) return "dex";
        if (name.endsWith(".apk")) return "apk";
        if (name.endsWith(".jar")) return "jar";
        if (name.endsWith(".zip")) return "zip";
        if (looksLikeDexFile(file)) return "dex";
        if (looksLikeZipFile(file)) return "zip";
        return "dex";
    }

    private static boolean looksLikeDexFile(@NonNull File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] head = new byte[4];
            return in.read(head) == 4 && looksLikeDexBytes(head);
        } catch (Throwable ignored) { return false; }
    }

    private static boolean looksLikeZipFile(@NonNull File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int a = in.read();
            int b = in.read();
            return a == 'P' && b == 'K';
        } catch (Throwable ignored) { return false; }
    }

    private static String safeArgString(XposedInterface.Chain chain, int index) {
        try {
            Object value = chain.getArg(index);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String describeTypes(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(types[i] == null ? "null" : types[i].getName());
        }
        return sb.append(')').toString();
    }

    private static void log(@NonNull HookEntry module, int priority, @NonNull String message, @Nullable Throwable tr) {
        if (!debugLogging && priority < Log.WARN) return;
        Log.println(priority, TAG, message + (tr == null ? "" : " : " + tr));
        try {
            if (tr == null) module.log(priority, TAG, message);
            else module.log(priority, TAG, message, tr);
        } catch (Throwable ignored) {
        }
    }

    private static final class LoaderRecord {
        final int loaderId;
        final String className;
        final String loaderText;
        final String origin;
        final long createdAt;
        final LinkedHashSet<String> sourceKeys = new LinkedHashSet<>();

        LoaderRecord(int loaderId, String className, String loaderText, String origin, long createdAt) {
            this.loaderId = loaderId;
            this.className = className;
            this.loaderText = loaderText;
            this.origin = origin;
            this.createdAt = createdAt;
        }
    }
}
