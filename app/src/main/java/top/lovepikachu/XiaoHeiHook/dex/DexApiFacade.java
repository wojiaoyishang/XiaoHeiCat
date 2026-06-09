package top.lovepikachu.XiaoHeiHook.dex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.MethodParameter;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.util.ReferenceUtil;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import android.util.Log;

import top.lovepikachu.XiaoHeiHook.HookEntry;
import top.lovepikachu.XiaoHeiHook.script.JsApiValueNormalizer;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JS facade exposed as global `dex`.
 *
 * First-version scope:
 * - dex.fromLoader(loader)
 * - dex.fromFile(path)
 * - dex.findMethod(query) / dex.findMethods(query)
 * - low-level class/method/string/invoke/instruction helpers
 */
public final class DexApiFacade {
    private static final String TAG = "XHH-DexApi";
    private static final long DEFAULT_MAX_DEX_BYTES = 256L * 1024L * 1024L;
    private static final int DEFAULT_MAX_CLASSES = 100_000;
    private static final int DEFAULT_MAX_METHODS = 1_000_000;
    private static final int DEFAULT_MAX_SMALI_CHARS = 500_000;
    private static final long HARD_MAX_DEX_BYTES = 1024L * 1024L * 1024L;
    private static final int HARD_MAX_CLASSES = 1_000_000;
    private static final int HARD_MAX_METHODS = 5_000_000;
    private static final int HARD_MAX_SMALI_CHARS = 5_000_000;

    private static volatile long maxDexBytes = DEFAULT_MAX_DEX_BYTES;
    private static volatile int maxClasses = DEFAULT_MAX_CLASSES;
    private static volatile int maxMethods = DEFAULT_MAX_METHODS;
    private static volatile int maxSmaliChars = DEFAULT_MAX_SMALI_CHARS;

    private static final Pattern QUOTED_PATH = Pattern.compile("(?:zip|dex) file \"([^\"]+)\"");

    private static Object js(Object value) {
        return JsApiValueNormalizer.toJs(value);
    }

    private static ArrayList<String> pathsFromRows(@Nullable List<? extends Map<String, Object>> rows) {
        ArrayList<String> paths = new ArrayList<>();
        if (rows == null) return paths;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            Object pathObj = row.get("path");
            if (pathObj == null) pathObj = row.get("scanPath");
            if (pathObj == null) pathObj = row.get("repairedPath");
            if (pathObj == null) continue;
            String path = String.valueOf(pathObj);
            if (!path.trim().isEmpty() && seen.add(path)) paths.add(path);
        }
        return paths;
    }

    private static ArrayList<Map<String, Object>> fileRowsFromFiles(@Nullable List<File> files) {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        if (files == null) return out;
        for (File file : files) {
            if (file == null) continue;
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("path", file.getAbsolutePath());
            row.put("name", file.getName());
            row.put("size", file.length());
            row.put("lastModified", file.lastModified());
            row.put("type", sourceType(file));
            out.add(row);
        }
        return out;
    }

    private static LinkedHashMap<String, Object> errorResult(@NonNull String reason, @NonNull String error) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("found", false);
        out.put("reason", reason);
        out.put("error", error);
        return out;
    }

    private final ClassLoader defaultLoader;
    @Nullable private final HookEntry module;
    private final String packageName;
    private final String processName;
    private final boolean debugLogging;

    public DexApiFacade(@NonNull ClassLoader defaultLoader) {
        this(null, defaultLoader, "", "", false);
    }

    public DexApiFacade(@Nullable HookEntry module, @NonNull ClassLoader defaultLoader, @Nullable String packageName, @Nullable String processName) {
        this(module, defaultLoader, packageName, processName, false);
    }

    public DexApiFacade(@Nullable HookEntry module, @NonNull ClassLoader defaultLoader, @Nullable String packageName, @Nullable String processName, boolean debugLogging) {
        this.module = module;
        this.defaultLoader = defaultLoader;
        this.packageName = packageName == null ? "" : packageName;
        this.processName = processName == null ? "" : processName;
        this.debugLogging = debugLogging;
        DexRuntimeRegistry.setDebugLogging(debugLogging);
        DexRuntimeRegistry.install(module, this.packageName, this.processName, defaultLoader);
        DexRuntimeRegistry.registerLoader(defaultLoader, "default-loader", DexSourceResolver.fromLoader(defaultLoader));
    }

    public DexFileView fromLoader(Object loaderObject) throws Exception {
        Object obj = unwrap(loaderObject);
        ClassLoader loader;
        if (obj == null || obj == Undefined.instance || obj == Scriptable.NOT_FOUND) {
            loader = defaultLoader;
        } else if (obj instanceof ClassLoader) {
            loader = (ClassLoader) obj;
        } else {
            throw new IllegalArgumentException("fromLoader(loader) 需要传入 ClassLoader；无参数调用才会使用默认 ClassLoader");
        }
        LinkedHashSet<DexSource> sources = new LinkedHashSet<>();
        sources.addAll(DexSourceResolver.fromLoader(loader));
        sources.addAll(DexRuntimeRegistry.sourcesForLoader(loader));
        if (sources.isEmpty()) {
            throw new IllegalStateException("未能从 ClassLoader/RuntimeRegistry 解析到 dex/apk 路径: " + loader);
        }
        return openSources(new ArrayList<>(sources));
    }

    public DexFileView fromFile(String path) throws Exception {
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("path 不能为空");
        File file = new File(path.trim());
        if (!file.isFile()) throw new IllegalArgumentException("文件不存在: " + file.getAbsolutePath());
        return openSources(Collections.singletonList(new DexSource(file.getAbsolutePath(), null, sourceType(file), null, "fromFile")));
    }

    /**
     * Inspect one dumped dex file for a target method.
     * This is intentionally not the generic search API. It answers whether the exported dex
     * actually contains the target class/method and whether the method body contains expected
     * strings/invokes. It is used to distinguish "dump/locate succeeded" from
     * "method body feature search succeeded".
     */
    public Object inspectMethodInFile(Object optionsObject) throws Exception {
        return js(inspectMethodInFileRaw(optionsObject));
    }

    private Map<String, Object> inspectMethodInFileRaw(Object optionsObject) throws Exception {
        Object obj = unwrap(optionsObject);
        String path = null;
        String className = null;
        String methodName = null;
        String proto = null;
        List<String> expectedStrings = new ArrayList<>();
        List<String> expectedInvokeContains = new ArrayList<>();
        int smaliChars = 6000;
        boolean verbose = debugLogging;
        if (obj instanceof String) {
            path = String.valueOf(obj);
        } else if (obj instanceof Scriptable) {
            Scriptable s = (Scriptable) obj;
            path = asString(get(s, "path"));
            if (path == null) path = asString(get(s, "file"));
            if (path == null) path = asString(get(s, "dexPath"));
            String v = asString(get(s, "className")); if (v == null) v = asString(get(s, "class")); if (v != null) className = v;
            v = asString(get(s, "methodName")); if (v == null) v = asString(get(s, "name")); if (v != null) methodName = v;
            v = asString(get(s, "proto")); if (v == null) v = asString(get(s, "descriptor")); if (v != null) proto = v;
            expectedStrings = asStringList(get(s, "strings"));
            expectedInvokeContains = asStringList(get(s, "invokeContains"));
            if (expectedInvokeContains.isEmpty()) expectedInvokeContains = asStringList(get(s, "invokesContains"));
            smaliChars = asInt(get(s, "smaliChars"), smaliChars);
            verbose = asBoolean(get(s, "verbose"), verbose);
        } else if (obj instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) obj;
            path = asString(mapGet(m, "path"));
            if (path == null) path = asString(mapGet(m, "file"));
            if (path == null) path = asString(mapGet(m, "dexPath"));
            String v = asString(mapGet(m, "className")); if (v == null) v = asString(mapGet(m, "class")); if (v != null) className = v;
            v = asString(mapGet(m, "methodName")); if (v == null) v = asString(mapGet(m, "name")); if (v != null) methodName = v;
            v = asString(mapGet(m, "proto")); if (v == null) v = asString(mapGet(m, "descriptor")); if (v != null) proto = v;
            expectedStrings = asStringList(mapGet(m, "strings"));
            expectedInvokeContains = asStringList(mapGet(m, "invokeContains"));
            if (expectedInvokeContains.isEmpty()) expectedInvokeContains = asStringList(mapGet(m, "invokesContains"));
            smaliChars = asInt(mapGet(m, "smaliChars"), smaliChars);
            verbose = asBoolean(mapGet(m, "verbose"), verbose);
        }
        if (path == null || path.trim().isEmpty()) {
            return errorResult("missing-path", "inspectMethodInFile requires path, className and methodName; use dex.findMethods for feature search");
        }
        if (className == null || className.trim().isEmpty()) {
            return errorResult("missing-className", "inspectMethodInFile requires path, className and methodName; use dex.findMethods for feature search");
        }
        if (methodName == null || methodName.trim().isEmpty()) {
            return errorResult("missing-methodName", "inspectMethodInFile requires path, className and methodName; use dex.findMethods for feature search");
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("path", path);
        out.put("className", className);
        out.put("methodName", methodName);
        out.put("proto", proto);
        out.put("expectedStrings", expectedStrings);
        out.put("expectedInvokeContains", expectedInvokeContains);
        File file = new File(path);
        out.put("exists", file.isFile());
        out.put("size", file.isFile() ? file.length() : 0L);
        long start = System.currentTimeMillis();
        if (!file.isFile()) {
            out.put("found", false);
            out.put("reason", "file-not-found");
            return out;
        }
        DexFileView df;
        try {
            df = fromFile(file.getAbsolutePath());
            out.put("opened", true);
            out.put("sources", df.sourcesRaw());
            out.put("skipped", df.skippedSourcesRaw());
        } catch (Throwable t) {
            out.put("opened", false);
            out.put("found", false);
            out.put("reason", "open-failed");
            out.put("error", brief(t));
            Log.w(TAG, "inspectMethodInFile open failed path=" + path, t);
            return out;
        }
        DexClassView cls = df.findClassByName(className);
        out.put("classFound", cls != null);
        if (cls == null) {
            out.put("found", false);
            out.put("reason", "class-not-found");
            out.put("elapsedMs", System.currentTimeMillis() - start);
            if (verbose) Log.i(TAG, "inspectMethodInFile class not found path=" + path + " class=" + className);
            return out;
        }
        out.put("classDescriptor", cls.descriptor);
        DexMethodView method = cls.findMethod(methodName, proto);
        if (method == null && (proto == null || proto.trim().isEmpty())) method = cls.findMethod(methodName, null);
        if (method == null) {
            ArrayList<String> methods = new ArrayList<>();
            for (DexMethodView m : cls.methodsRaw()) if (methodName.equals(m.name)) methods.add(m.name + m.descriptor);
            out.put("found", false);
            out.put("methodFound", false);
            out.put("reason", "method-not-found");
            out.put("sameNameMethods", methods);
            out.put("elapsedMs", System.currentTimeMillis() - start);
            if (verbose) Log.i(TAG, "inspectMethodInFile method not found path=" + path + " target=" + className + "." + methodName + proto + " sameName=" + methods);
            return out;
        }
        out.put("found", true);
        out.put("methodFound", true);
        out.put("descriptor", method.descriptor);
        out.put("returnType", method.returnType);
        out.put("parameters", method.parametersRaw());
        out.put("static", method.isStatic());
        List<String> strings = method.stringsRaw();
        List<String> invokes = method.invokesRaw();
        out.put("strings", strings);
        out.put("invokes", invokes);
        ArrayList<String> missingStrings = new ArrayList<>();
        for (String expected : expectedStrings) if (expected != null && !strings.contains(expected)) missingStrings.add(expected);
        ArrayList<String> missingInvokes = new ArrayList<>();
        for (String part : expectedInvokeContains) {
            boolean ok = false;
            for (String actual : invokes) if (actual != null && actual.contains(part)) { ok = true; break; }
            if (!ok) missingInvokes.add(part);
        }
        out.put("missingStrings", missingStrings);
        out.put("missingInvokeContains", missingInvokes);
        out.put("ok", true);
        out.put("featuresOk", missingStrings.isEmpty() && missingInvokes.isEmpty());
        String smali = method.smali();
        if (smali == null) smali = "";
        if (smaliChars > 0 && smali.length() > smaliChars) smali = smali.substring(0, smaliChars);
        out.put("smaliHead", smali);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        if (verbose) Log.i(TAG, "inspectMethodInFile result path=" + path
                + " target=" + className + "." + methodName + method.descriptor
                + " found=true featuresOk=" + out.get("featuresOk")
                + " strings=" + strings
                + " missingStrings=" + missingStrings
                + " missingInvokeContains=" + missingInvokes
                + " elapsedMs=" + out.get("elapsedMs"));
        return out;
    }

    /**
     * Minimal locator for an already dumped cookie dex directory.
     * It does not dump, does not raw-scan business strings, and does not open all dex files at once.
     * It simply walks cookie_*.dex one by one and returns the first dex that defines the requested method.
     */
    public Object locateMethodInCookieDumps() { return locateMethodInCookieDumps(null); }

    public Object locateMethodInCookieDumps(Object optionsObject) {
        String targetClassName = null;
        String targetMethodName = null;
        String targetProto = null;
        String dirPath = defaultCookieDumpDir(packageName);
        String prefix = "cookie_";
        int maxFiles = 300;
        long localMaxDexBytes = Math.max(maxDexBytes, 512L * 1024L * 1024L);
        boolean includeSmali = true;
        int maxSmali = 200_000;
        boolean verbose = debugLogging;

        Object opts = unwrap(optionsObject);
        if (opts instanceof Scriptable) {
            Scriptable scriptable = (Scriptable) opts;
            String v;
            v = asString(get(scriptable, "dir")); if (v == null) v = asString(get(scriptable, "path")); if (v != null) dirPath = v;
            v = asString(get(scriptable, "className")); if (v == null) v = asString(get(scriptable, "class")); if (v != null) targetClassName = v;
            v = asString(get(scriptable, "methodName")); if (v == null) v = asString(get(scriptable, "name")); if (v != null) targetMethodName = v;
            v = asString(get(scriptable, "proto")); if (v == null) v = asString(get(scriptable, "descriptor")); if (v != null) targetProto = v;
            v = asString(get(scriptable, "prefix")); if (v != null) prefix = v;
            maxFiles = asInt(get(scriptable, "maxFiles"), maxFiles);
            localMaxDexBytes = asLong(get(scriptable, "maxDexBytes"), localMaxDexBytes);
            includeSmali = asBoolean(get(scriptable, "includeSmali"), includeSmali);
            maxSmali = asInt(get(scriptable, "maxSmaliChars"), maxSmali);
            verbose = asBoolean(get(scriptable, "verbose"), verbose);
        } else if (opts instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) opts;
            String v;
            v = asString(mapGet(map, "dir")); if (v == null) v = asString(mapGet(map, "path")); if (v != null) dirPath = v;
            v = asString(mapGet(map, "className")); if (v == null) v = asString(mapGet(map, "class")); if (v != null) targetClassName = v;
            v = asString(mapGet(map, "methodName")); if (v == null) v = asString(mapGet(map, "name")); if (v != null) targetMethodName = v;
            v = asString(mapGet(map, "proto")); if (v == null) v = asString(mapGet(map, "descriptor")); if (v != null) targetProto = v;
            v = asString(mapGet(map, "prefix")); if (v != null) prefix = v;
            maxFiles = asInt(mapGet(map, "maxFiles"), maxFiles);
            localMaxDexBytes = asLong(mapGet(map, "maxDexBytes"), localMaxDexBytes);
            includeSmali = asBoolean(mapGet(map, "includeSmali"), includeSmali);
            maxSmali = asInt(mapGet(map, "maxSmaliChars"), maxSmali);
            verbose = asBoolean(mapGet(map, "verbose"), verbose);
        }
        return js(locateMethodInCookieDumpsInternal(
                dirPath, prefix, targetClassName, targetMethodName, targetProto,
                maxFiles, localMaxDexBytes, includeSmali, maxSmali, verbose
        ));
    }

    /**
     * Single-path unpack flow for the proven LSPosed scenario.
     *
     * Flow:
     *   appClassLoader from Application.attach -> load target class -> dump DexFile cookies
     *   -> locate target method in the dumped cookie dex -> copy that dex to a stable export path.
     *
     * It deliberately does not use memory scanning, class-dex fallback, raw salvage, or broad
     * string search.  The goal is to produce the already-unpacked business dex that actually
     * defines the requested method.
     */
    public Object dumpDecryptedDexForMethod() { return dumpDecryptedDexForMethod(null); }

    public Object dumpDecryptedDexForMethod(Object optionsObject) {
        return js(dumpDecryptedDexForMethodRaw(optionsObject));
    }

    private Map<String, Object> dumpDecryptedDexForMethodRaw(Object optionsObject) {
        Map<String, Object> legacyCleanup = cleanupLegacyCookieDirs(packageName);
        DumpDecryptedTargetOptions options = DumpDecryptedTargetOptions.from(optionsObject, packageName);
        if (debugLogging) options.verbose = true;
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        long started = System.currentTimeMillis();
        out.put("ok", false);
        out.put("strategy", "app-loader-cookie-dump-locate-export");
        out.put("className", options.className);
        out.put("methodName", options.methodName);
        out.put("proto", options.proto);
        out.put("cookieDir", options.cookieDir);
        out.put("outputDir", options.outputDir);
        out.put("legacyCookieCleanup", legacyCleanup);
        out.put("found", false);

        ClassLoader loader = options.loader != null ? options.loader : defaultLoader;
        out.put("loader", String.valueOf(loader));
        out.put("loaderId", loader == null ? null : System.identityHashCode(loader));
        if (loader == null) {
            out.put("error", "没有可用 ClassLoader；请在 Application.attach(Context) 后传入 context.getClassLoader()");
            Log.w(TAG, "dumpDecryptedDexForMethod stop: no loader target=" + options.className + "." + options.methodName + options.proto);
            return out;
        }

        DexClassLoadDumper.rememberAppClassLoader(loader, "dex.dumpDecryptedDexForMethod.loader", module);
        if (options.verbose) Log.i(TAG, "dumpDecryptedDexForMethod start target=" + options.className + "." + options.methodName + options.proto
                + " loader=" + loader
                + " cookieDir=" + options.cookieDir
                + " outputDir=" + options.outputDir
                + " clearCookieDir=" + options.clearCookieDir);

        // Step 1: force-load the target class through the real app ClassLoader.  If this fails,
        // the loader/timing is wrong; do not try unrelated fallback strategies.
        try {
            Class<?> c = loadClassWith(loader, options.className);
            out.put("loadedClass", String.valueOf(c));
            out.put("loadedClassLoader", c == null ? null : String.valueOf(c.getClassLoader()));
            if (options.verbose) Log.i(TAG, "dumpDecryptedDexForMethod loadClass OK class=" + c + " classLoader=" + (c == null ? null : c.getClassLoader()));
        } catch (Throwable t) {
            out.put("error", "loadClass failed: " + String.valueOf(t));
            Log.w(TAG, "dumpDecryptedDexForMethod loadClass failed target=" + options.className, t);
            return out;
        }

        File cookieDir = new File(options.cookieDir);
        if (options.clearCookieDir) {
            Map<String, Object> clear = clearDexFiles(cookieDir, options.cookiePrefix);
            out.put("clearCookieDir", clear);
            if (options.verbose) Log.i(TAG, "dumpDecryptedDexForMethod clear cookie dir deleted=" + clear.get("deleted") + " failed=" + clear.get("failed"));
        } else {
            ensureDir(cookieDir, "cookie dex dump 目录");
        }

        // Step 2: dump only from this app ClassLoader's DexFile cookies.
        DexCookieDumper.Result dumped;
        try {
            dumped = DexCookieDumper.dump(
                    loader,
                    options.cookieDir,
                    options.maxDexBytes,
                    options.maxDumpCount,
                    options.includeParents,
                    options.includeThreadContext,
                    options.verbose
            );
            out.put("dumpCount", dumped.dumps.size());
            out.put("paths", pathsFromRows(dumped.dumps));
            out.put("dumpedPaths", pathsFromRows(dumped.dumps));
            out.put("cookieValueCount", dumped.cookieValueCount);
            out.put("dexFileObjectCount", dumped.dexFileObjectCount);
            out.put("dumpSources", dumped.dumps);
            if (options.verbose) Log.i(TAG, "dumpDecryptedDexForMethod cookie dump done count=" + dumped.dumps.size()
                    + " dexFileObjects=" + dumped.dexFileObjectCount
                    + " cookieValues=" + dumped.cookieValueCount);
            cleanupLegacyCookieDirs(packageName);
        } catch (Throwable t) {
            out.put("error", "cookie dump failed: " + String.valueOf(t));
            Log.w(TAG, "dumpDecryptedDexForMethod cookie dump failed", t);
            return out;
        }

        // Step 3: locate the exact method in the freshly dumped cookie dex directory.
        Map<String, Object> located = locateMethodInCookieDumpsInternal(
                options.cookieDir,
                options.cookiePrefix,
                options.className,
                options.methodName,
                options.proto,
                options.maxFiles,
                options.maxDexBytes,
                options.includeSmali,
                options.maxSmaliChars,
                options.verbose
        );
        out.put("locate", located);
        Object found = located.get("found");
        if (!Boolean.TRUE.equals(found)) {
            out.put("elapsedMs", System.currentTimeMillis() - started);
            out.put("error", "cookie dump 完成，但没有在新 dump 的 dex 中定位到目标方法");
            Log.w(TAG, "dumpDecryptedDexForMethod not found after dump target=" + options.className + "." + options.methodName + options.proto
                    + " dumpCount=" + out.get("dumpCount"));
            return out;
        }

        // Step 4: export/copy the located dex to a stable path. This is the unpacked business dex.
        String sourcePath = asString(located.get("path"));
        File source = sourcePath == null ? null : new File(sourcePath);
        File outDir = new File(options.outputDir);
        ensureDir(outDir, "脱壳 dex 输出目录");
        File exported = new File(outDir, options.exportName);
        try {
            copyFile(source, exported);
            out.put("ok", true);
            out.put("found", true);
            out.put("sourcePath", source == null ? null : source.getAbsolutePath());
            out.put("sourceSize", source == null ? null : source.length());
            out.put("exportedPath", exported.getAbsolutePath());
            out.put("paths", Collections.singletonList(exported.getAbsolutePath()));
            out.put("exportedSize", exported.length());
            out.put("elapsedMs", System.currentTimeMillis() - started);
            if (options.verbose) Log.i(TAG, "dumpDecryptedDexForMethod EXPORTED targetDex=" + exported.getAbsolutePath()
                    + " source=" + source.getAbsolutePath()
                    + " size=" + exported.length()
                    + " elapsedMs=" + out.get("elapsedMs"));
        } catch (Throwable t) {
            out.put("found", true);
            out.put("sourcePath", source == null ? null : source.getAbsolutePath());
            out.put("exportError", String.valueOf(t));
            out.put("elapsedMs", System.currentTimeMillis() - started);
            Log.w(TAG, "dumpDecryptedDexForMethod export failed source=" + sourcePath + " dest=" + exported.getAbsolutePath(), t);
        }
        return out;
    }


    /**
     * One-call stable path: dump the unpacked dex containing target method, then inspect
     * that exact exported dex. No memory scan / raw salvage / broad search fallback is used.
     */
    public Object dumpAndInspectMethod() { return dumpAndInspectMethod(null); }

    public Object dumpAndInspectMethod(Object optionsObject) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> dump = dumpDecryptedDexForMethodRaw(optionsObject);
        out.put("dump", dump);
        out.put("dumpFound", dump.get("found"));
        Object exportedPath = dump.get("exportedPath");
        if (!Boolean.TRUE.equals(dump.get("found")) || exportedPath == null) {
            out.put("inspect", null);
            out.put("featuresOk", false);
            return js(out);
        }
        LinkedHashMap<String, Object> inspectOptions = optionMapForInspect(optionsObject);
        inspectOptions.put("path", exportedPath);
        try {
            Map<String, Object> inspect = inspectMethodInFileRaw(inspectOptions);
            out.put("inspect", inspect);
            out.put("featuresOk", inspect.get("featuresOk"));
        } catch (Throwable t) {
            out.put("inspectError", brief(t));
            out.put("featuresOk", false);
        }
        return js(out);
    }


    private LinkedHashMap<String, Object> optionMapForInspect(Object optionsObject) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        Object obj = unwrap(optionsObject);
        if (obj instanceof Scriptable) {
            Scriptable s = (Scriptable) obj;
            copyIfPresent(out, "className", get(s, "className"));
            copyIfPresent(out, "class", get(s, "class"));
            copyIfPresent(out, "methodName", get(s, "methodName"));
            copyIfPresent(out, "name", get(s, "name"));
            copyIfPresent(out, "proto", get(s, "proto"));
            copyIfPresent(out, "descriptor", get(s, "descriptor"));
            copyIfPresent(out, "strings", get(s, "strings"));
            copyIfPresent(out, "invokeContains", get(s, "invokeContains"));
            copyIfPresent(out, "invokesContains", get(s, "invokesContains"));
            copyIfPresent(out, "smaliChars", get(s, "smaliChars"));
            copyIfPresent(out, "verbose", get(s, "verbose"));
        } else if (obj instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) obj;
            for (String key : new String[]{"className","class","methodName","name","proto","descriptor","strings","invokeContains","invokesContains","smaliChars","verbose"}) {
                copyIfPresent(out, key, mapGet(m, key));
            }
        }
        return out;
    }

    private static void copyIfPresent(Map<String, Object> out, String key, Object value) {
        if (value != null && value != Undefined.instance && value != Scriptable.NOT_FOUND) out.put(key, value);
    }

    private Map<String, Object> locateMethodInCookieDumpsInternal(String dirPath,
                                                                  String prefix,
                                                                  String targetClassName,
                                                                  String targetMethodName,
                                                                  String targetProto,
                                                                  int maxFiles,
                                                                  long localMaxDexBytes,
                                                                  boolean includeSmali,
                                                                  int maxSmali,
                                                                  boolean verbose) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        ArrayList<Map<String, Object>> scanned = new ArrayList<>();
        ArrayList<Map<String, Object>> errors = new ArrayList<>();
        if (targetClassName == null || targetClassName.trim().isEmpty()) {
            out.put("error", "className 不能为空；locateMethodInCookieDumpsInternal 需要指定目标类");
            out.put("reason", "missing-className");
            return out;
        }
        if (targetMethodName == null || targetMethodName.trim().isEmpty()) {
            out.put("error", "methodName 不能为空；locateMethodInCookieDumpsInternal 需要指定目标方法");
            out.put("reason", "missing-methodName");
            return out;
        }
        if (targetProto != null && targetProto.trim().isEmpty()) targetProto = null;
        if (dirPath == null || dirPath.trim().isEmpty() || isLegacyCookieDir(dirPath)) dirPath = defaultCookieDumpDir(packageName);
        if (prefix == null) prefix = "cookie_";
        if (maxFiles <= 0) maxFiles = 300;
        if (localMaxDexBytes < 0x70) localMaxDexBytes = Math.max(maxDexBytes, 512L * 1024L * 1024L);
        if (localMaxDexBytes > HARD_MAX_DEX_BYTES) localMaxDexBytes = HARD_MAX_DEX_BYTES;
        if (maxSmali <= 0) maxSmali = 200_000;

        String targetDescriptor = DexProtoUtils.toDescriptor(targetClassName);
        long started = System.currentTimeMillis();
        File dir = new File(dirPath);
        out.put("dir", dirPath);
        out.put("className", targetClassName);
        out.put("classDescriptor", targetDescriptor);
        out.put("methodName", targetMethodName);
        out.put("proto", targetProto);
        out.put("ok", false);
        out.put("found", false);
        out.put("scanned", scanned);
        out.put("errors", errors);

        if (verbose) Log.i(TAG, "locateMethodInCookieDumps start dir=" + dirPath
                + " target=" + targetClassName + "." + targetMethodName + targetProto
                + " prefix=" + prefix + " maxFiles=" + maxFiles);

        if (!dir.exists() || !dir.isDirectory()) {
            out.put("error", "目录不存在或不是目录: " + dirPath);
            Log.w(TAG, "locateMethodInCookieDumps stop: dir missing " + dirPath);
            return out;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            out.put("error", "目录不可列举/listFiles 返回 null: " + dirPath);
            Log.w(TAG, "locateMethodInCookieDumps stop: listFiles null " + dirPath);
            return out;
        }

        ArrayList<File> dexFiles = new ArrayList<>();
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (prefix != null && !prefix.isEmpty() && !name.startsWith(prefix)) continue;
            if (!name.endsWith(".dex")) continue;
            long len = f.length();
            if (len < 0x70) continue;
            if (len > localMaxDexBytes) {
                LinkedHashMap<String, Object> err = new LinkedHashMap<>();
                err.put("path", f.getAbsolutePath());
                err.put("stage", "filter-size");
                err.put("size", len);
                err.put("error", "dex too large, maxDexBytes=" + localMaxDexBytes);
                errors.add(err);
                continue;
            }
            dexFiles.add(f);
        }
        Collections.sort(dexFiles, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        out.put("totalFiles", files.length);
        out.put("candidateFiles", dexFiles.size());
        if (verbose) Log.i(TAG, "locateMethodInCookieDumps files total=" + files.length + " candidates=" + dexFiles.size());

        int limit = Math.min(maxFiles <= 0 ? dexFiles.size() : maxFiles, dexFiles.size());
        int opened = 0;
        for (int i = 0; i < limit; i++) {
            File file = dexFiles.get(i);
            long t0 = System.currentTimeMillis();
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("path", file.getAbsolutePath());
            row.put("name", file.getName());
            row.put("size", file.length());
            scanned.add(row);
            if (verbose) Log.i(TAG, "locateMethodInCookieDumps [" + (i + 1) + "/" + limit + "] open "
                    + file.getName() + " size=" + file.length());
            try {
                DexBackedDexFile dexFile = DexFileFactory.loadDexFile(file, Opcodes.getDefault());
                opened++;
                int classCount = 0;
                int methodCount = 0;
                boolean classFound = false;
                for (ClassDef classDef : dexFile.getClasses()) {
                    classCount++;
                    String type = classDef.getType();
                    if (!targetDescriptor.equals(type)) continue;
                    classFound = true;
                    row.put("classFound", true);
                    if (verbose) Log.i(TAG, "locateMethodInCookieDumps class found in " + file.getName() + " type=" + type);
                    for (Method method : classDef.getMethods()) {
                        methodCount++;
                        String proto = DexProtoUtils.proto(method);
                        if (targetMethodName.equals(method.getName()) && (targetProto == null || targetProto.equals(proto))) {
                            long elapsed = System.currentTimeMillis() - t0;
                            row.put("methodFound", true);
                            row.put("elapsedMs", elapsed);
                            out.put("ok", true);
                            out.put("found", true);
                            out.put("path", file.getAbsolutePath());
                            out.put("fileName", file.getName());
                            out.put("size", file.length());
                            out.put("classFound", true);
                            out.put("methodFound", true);
                            out.put("accessFlags", method.getAccessFlags());
                            out.put("returnType", method.getReturnType());
                            out.put("parameters", new ArrayList<CharSequence>(method.getParameterTypes()));
                            out.put("elapsedMs", System.currentTimeMillis() - started);
                            if (includeSmali) out.put("smali", DexSmaliPrinter.method(method, maxSmali));
                            if (verbose) Log.i(TAG, "locateMethodInCookieDumps FOUND path=" + file.getAbsolutePath()
                                    + " target=" + targetClassName + "." + targetMethodName + targetProto
                                    + " elapsedMs=" + elapsed);
                            return out;
                        }
                    }
                    break;
                }
                row.put("classCount", classCount);
                row.put("methodCountInTargetClass", methodCount);
                row.put("classFound", classFound);
                row.put("elapsedMs", System.currentTimeMillis() - t0);
                if (verbose) Log.i(TAG, "locateMethodInCookieDumps [" + (i + 1) + "/" + limit + "] done "
                        + file.getName() + " classFound=" + classFound + " elapsedMs=" + row.get("elapsedMs"));
            } catch (Throwable t) {
                row.put("error", String.valueOf(t));
                row.put("elapsedMs", System.currentTimeMillis() - t0);
                LinkedHashMap<String, Object> err = new LinkedHashMap<>();
                err.put("path", file.getAbsolutePath());
                err.put("stage", "open-or-scan");
                err.put("error", String.valueOf(t));
                errors.add(err);
                Log.w(TAG, "locateMethodInCookieDumps skip " + file.getAbsolutePath(), t);
            } finally {
                if ((i + 1) % 8 == 0) {
                    try { System.gc(); } catch (Throwable ignored) {}
                }
            }
        }
        out.put("opened", opened);
        out.put("elapsedMs", System.currentTimeMillis() - started);
        if (verbose) Log.w(TAG, "locateMethodInCookieDumps NOT_FOUND target=" + targetClassName + "." + targetMethodName + targetProto
                + " candidates=" + dexFiles.size() + " scanned=" + limit + " opened=" + opened
                + " elapsedMs=" + out.get("elapsedMs"));
        return out;
    }

    private static void ensureDir(@Nullable File dir, @NonNull String label) {
        if (dir == null) throw new IllegalArgumentException(label + " 不能为空");
        if (dir.exists()) {
            if (!dir.isDirectory()) throw new IllegalStateException(label + " 不是目录: " + dir.getAbsolutePath());
            return;
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("无法创建" + label + ": " + dir.getAbsolutePath());
        }
    }

    private static Map<String, Object> clearDexFiles(@NonNull File dir, @Nullable String prefix) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("dir", dir.getAbsolutePath());
        int deleted = 0;
        int failed = 0;
        if (!dir.exists()) {
            if (!dir.mkdirs() && !dir.isDirectory()) {
                out.put("created", false);
                out.put("error", "无法创建目录");
                return out;
            }
            out.put("created", true);
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f == null || !f.isFile()) continue;
                String name = f.getName();
                if (prefix != null && !prefix.isEmpty() && !name.startsWith(prefix)) continue;
                if (!name.endsWith(".dex")) continue;
                if (f.delete()) deleted++; else failed++;
            }
        }
        out.put("deleted", deleted);
        out.put("failed", failed);
        return out;
    }

    private static void copyFile(@Nullable File source, @NonNull File dest) throws Exception {
        if (source == null || !source.isFile()) throw new IllegalArgumentException("source dex 不存在: " + source);
        File parent = dest.getParentFile();
        if (parent != null) ensureDir(parent, "输出目录");
        byte[] buffer = new byte[1024 * 1024];
        try (java.io.FileInputStream in = new java.io.FileInputStream(source);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest, false)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            out.flush();
        }
    }

    /**
     * Scan readable in-process memory regions for dex magic, dump valid dex images, and register
     * them as runtime sources.  This is intentionally explicit because memory scanning can be
     * expensive; scripts should call it only after the target app has finished unpacking/loading.
     */
    public Object scanMemory() { return scanMemory(null); }

    public Object scanMemory(Object optionsObject) {
        return js(scanMemoryInternal(optionsObject, true));
    }

    /**
     * Dump-only variant. It does not require target-method hooks and does not register the dumped
     * files into RuntimeRegistry by default. Read returned paths directly or pass an exact path
     * to dex.findMethods({ path: ... }).
     */
    public Object dumpMemory() { return dumpMemory(null); }

    public Object dumpMemory(Object optionsObject) {
        return js(scanMemoryInternal(optionsObject, false));
    }

    /**
     * Raw/salvage dump mode. Unlike dumpMemory(), this keeps dex-like candidates
     * even when section/map/string validation fails. Use this when external tools
     * show that the useful business dex came from an earlier "bad" dump.
     */
    public Object dumpMemoryRaw() { return dumpMemoryRaw(null); }

    public Object dumpMemoryRaw(Object optionsObject) {
        return js(scanMemoryRawInternal(optionsObject, false));
    }


    /**
     * BlackDex-style cookie dumper. It walks live ClassLoader DexPathList
     * elements, extracts dalvik.system.DexFile.mCookie values, then asks the
     * native layer to dump the ART DexFile begin_/size_ memory region.
     */
    public Object dumpDexCookies() { return dumpDexCookies(null); }

    public Object dumpDexCookies(Object optionsObject) {
        Map<String, Object> legacyCleanup = cleanupLegacyCookieDirs(packageName);
        CookieDumpOptions options = CookieDumpOptions.from(optionsObject, packageName);
        if (debugLogging) options.verbose = true;
        ClassLoader cookieRootLoader = options.loader != null ? options.loader : defaultLoader;
        if (options.loader != null) DexClassLoadDumper.rememberAppClassLoader(options.loader, "dex.dumpDexCookies.options", module);
        DexCookieDumper.Result result = DexCookieDumper.dump(
                cookieRootLoader,
                options.outputDir,
                options.maxDexBytes,
                options.maxDumpCount,
                options.includeParents,
                options.includeThreadContext,
                options.verbose
        );
        if (options.registerSources) {
            for (Map<String, Object> map : result.dumps) {
                Object path = map.get("path");
                if (path != null) DexRuntimeRegistry.registerPath(cookieRootLoader, String.valueOf(path), "dexfile-cookie");
            }
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", "dexfile-cookie");
        out.put("outputDir", options.outputDir);
        out.put("ok", true);
        out.put("count", result.dumps.size());
        ArrayList<String> dumpedPaths = pathsFromRows(result.dumps);
        out.put("paths", dumpedPaths);
        out.put("dumpedPaths", dumpedPaths);
        out.put("sources", result.dumps);
        out.put("loader", String.valueOf(cookieRootLoader));
        out.put("loaderId", cookieRootLoader == null ? null : System.identityHashCode(cookieRootLoader));
        out.put("loaderCount", result.loaderCount);
        out.put("dexFileObjectCount", result.dexFileObjectCount);
        out.put("cookieValueCount", result.cookieValueCount);
        out.put("cookies", result.cookieInfos);
        out.put("legacyCookieCleanup", legacyCleanup);
        out.put("maxDexBytes", options.maxDexBytes);
        out.put("maxDumpCount", options.maxDumpCount);
        out.put("includeParents", options.includeParents);
        out.put("includeThreadContext", options.includeThreadContext);
        out.put("registerSources", options.registerSources);
        return js(out);
    }


    /** dumpDex-style Java layer status: class-load hooks dump Class.dexCache.getDex().getBytes(). */
    public Object classLoadDumpStatus() { return js(DexClassLoadDumper.status()); }
    public Object classDexDumpStatus() { return js(DexClassLoadDumper.status()); }
    public String classDexDumpDir() { return DexClassLoadDumper.outputDir(); }
    public Object classDexDumpSources() { return js(DexClassLoadDumper.sources()); }

    /** Force-load/probe known class names with the selected ClassLoader and dump their Class.dexCache dex if available. */
    public Object dumpClassDex(Object classNameOrArray) {
        return js(dumpClassDexInternal(classNameOrArray, null));
    }

    /** Rhino-friendly overload: dex.dumpClassDex(["com.example.Target"], loader). */
    public Object dumpClassDex(Object classNameOrArray, Object loaderObject) {
        return js(dumpClassDexInternal(classNameOrArray, loaderObject));
    }

    public Object dumpLoadedClassDex(Object classObject) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        int ok = 0;
        Object obj = unwrap(classObject);
        if (obj instanceof Class<?>) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            Class<?> c = (Class<?>) obj;
            row.put("className", c.getName());
            row.put("loader", String.valueOf(c.getClassLoader()));
            try {
                if (c.getClassLoader() != null) DexClassLoadDumper.rememberAppClassLoader(c.getClassLoader(), "dex.dumpLoadedClassDex", module);
                String path = DexClassLoadDumper.dumpClassDex(c, "dex.dumpLoadedClassDex", module);
                row.put("path", path);
                row.put("ok", path != null);
                if (path != null) ok++;
            } catch (Throwable t) {
                row.put("ok", false);
                row.put("error", String.valueOf(t));
            }
            rows.add(row);
        } else {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("ok", false);
            row.put("error", "参数不是 java.lang.Class: " + obj);
            rows.add(row);
        }
        out.put("ok", ok > 0);
        out.put("count", ok);
        out.put("paths", pathsFromRows(rows));
        out.put("requested", rows.size());
        out.put("results", rows);
        out.put("sources", DexClassLoadDumper.sources());
        out.put("status", DexClassLoadDumper.status());
        return js(out);
    }

    public Object rememberAppClassLoader(Object loaderObject) {
        ClassLoader loader = asClassLoader(loaderObject, null);
        DexClassLoadDumper.rememberAppClassLoader(loader, "dex.rememberAppClassLoader", module);
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("ok", loader != null);
        out.put("loader", String.valueOf(loader));
        out.put("loaderId", loader == null ? null : System.identityHashCode(loader));
        out.put("status", DexClassLoadDumper.status());
        return js(out);
    }

    public Object appClassLoaders() {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (ClassLoader loader : DexClassLoadDumper.appClassLoaders()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("id", loader == null ? null : System.identityHashCode(loader));
            row.put("loader", String.valueOf(loader));
            row.put("class", loader == null ? null : loader.getClass().getName());
            out.add(row);
        }
        return js(out);
    }

    private Map<String, Object> dumpClassDexInternal(Object classNameOrArray, Object explicitLoaderObject) {
        Object raw = unwrap(classNameOrArray);
        ClassLoader loader = asClassLoader(explicitLoaderObject, null);
        List<String> names = Collections.emptyList();
        ArrayList<Class<?>> classes = new ArrayList<>();
        String origin = "dex.dumpClassDex";

        if (raw instanceof Scriptable) {
            Scriptable s = (Scriptable) raw;
            if (loader == null) loader = asClassLoader(get(s, "loader"), null);
            if (loader == null) loader = asClassLoader(get(s, "classLoader"), null);
            if (loader == null) loader = asClassLoader(get(s, "appClassLoader"), null);
            names = asStringList(get(s, "classes"));
            if (names.isEmpty()) names = asStringList(get(s, "classNames"));
            if (names.isEmpty()) names = asStringList(get(s, "names"));
            if (names.isEmpty()) names = asStringList(get(s, "className"));
            Object classObj = get(s, "classObject");
            if (classObj == null) classObj = get(s, "clazz");
            if (unwrap(classObj) instanceof Class<?>) classes.add((Class<?>) unwrap(classObj));
            String o = asString(get(s, "origin"));
            if (o != null) origin = o;
        } else if (raw instanceof Class<?>) {
            classes.add((Class<?>) raw);
        } else {
            names = asStringList(raw);
        }
        if (loader == null) loader = defaultLoader;
        if (loader != null) DexClassLoadDumper.rememberAppClassLoader(loader, origin + ".loader", module);

        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        int ok = 0;

        for (Class<?> c : classes) {
            LinkedHashMap<String, Object> row = dumpOneClass(c, origin, loader);
            if (Boolean.TRUE.equals(row.get("ok"))) ok++;
            rows.add(row);
        }
        for (String name : names) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("className", name);
            row.put("loader", String.valueOf(loader));
            row.put("loaderId", loader == null ? null : System.identityHashCode(loader));
            try {
                Class<?> c = loadClassWith(loader, name);
                row.put("resolvedClass", String.valueOf(c));
                row.put("resolvedLoader", c == null ? null : String.valueOf(c.getClassLoader()));
                LinkedHashMap<String, Object> dumped = dumpOneClass(c, origin, loader);
                row.putAll(dumped);
                if (Boolean.TRUE.equals(row.get("ok"))) ok++;
            } catch (Throwable t) {
                row.put("ok", false);
                row.put("error", String.valueOf(t));
            }
            rows.add(row);
        }
        out.put("ok", ok > 0);
        out.put("count", ok);
        out.put("paths", pathsFromRows(rows));
        ArrayList<Map<String, Object>> dumpedRows = new ArrayList<>();
        ArrayList<Map<String, Object>> failedRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (Boolean.TRUE.equals(row.get("ok"))) dumpedRows.add(row); else failedRows.add(row);
        }
        out.put("dumped", dumpedRows);
        out.put("failed", failedRows);
        out.put("requested", rows.size());
        out.put("loader", String.valueOf(loader));
        out.put("loaderId", loader == null ? null : System.identityHashCode(loader));
        out.put("results", rows);
        out.put("sources", DexClassLoadDumper.sources());
        out.put("status", DexClassLoadDumper.status());
        return out;
    }

    private LinkedHashMap<String, Object> dumpOneClass(Class<?> c, String origin, ClassLoader requestedLoader) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        if (c == null) {
            row.put("ok", false);
            row.put("error", "class resolved null");
            return row;
        }
        row.put("className", c.getName());
        row.put("requestedLoader", String.valueOf(requestedLoader));
        row.put("classLoader", String.valueOf(c.getClassLoader()));
        try {
            if (c.getClassLoader() != null) DexClassLoadDumper.rememberAppClassLoader(c.getClassLoader(), origin + ".class", module);
            String path = DexClassLoadDumper.dumpClassDex(c, origin, module);
            row.put("path", path);
            row.put("ok", path != null);
            if (path == null) row.put("note", "Class.dexCache bytes not available; cookie fallback may have run. Check status/sources.");
        } catch (Throwable t) {
            row.put("ok", false);
            row.put("error", String.valueOf(t));
        }
        return row;
    }

    private Class<?> loadClassWith(ClassLoader loader, String name) throws Exception {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("className 为空");
        ClassLoader effective = loader == null ? defaultLoader : loader;
        try { return Class.forName(name.trim(), false, effective); }
        catch (Throwable ignored) { return effective.loadClass(name.trim()); }
    }

    public Object dumpClassDex() { return dumpClassDex(Collections.emptyList()); }

    public DexFileView fromClassDumps() throws Exception { return fromClassDumps(null); }
    public DexFileView fromClassDumps(Object optionsObject) throws Exception {
        ArrayList<DexSource> sources = new ArrayList<>();
        LinkedHashSet<String> seenPaths = new LinkedHashSet<>();
        for (Map<String, Object> map : DexClassLoadDumper.sources()) {
            Object path = map == null ? null : map.get("path");
            if (path == null) continue;
            File f = new File(String.valueOf(path));
            if (f.isFile() && seenPaths.add(f.getAbsolutePath())) {
                sources.add(new DexSource(f.getAbsolutePath(), null, sourceType(f), defaultLoader, "class-dex-cache"));
            }
        }

        // Do not trust only the in-memory DUMPS table. A script may run after the dump happened.
        // Scan configured class-dump output and the unified DumpDex output directory.
        DumpDirOptions options = DumpDirOptions.from(optionsObject, packageName);
        if (debugLogging) options.verbose = true;
        String configuredDir = DexClassLoadDumper.outputDir();
        options.dir = configuredDir;
        options.prefix = null;
        options.dedupe = false;
        Object obj = unwrap(optionsObject);
        if (obj instanceof Scriptable) {
            Scriptable s = (Scriptable) obj;
            String dir = asString(get(s, "dir"));
            if (dir == null) dir = asString(get(s, "path"));
            if (dir == null) dir = asString(get(s, "outputDir"));
            if (dir != null) options.dir = dir;
            String prefix = asString(get(s, "prefix"));
            if (prefix != null) options.prefix = prefix;
        }

        ArrayList<Map<String, Object>> diagnostics = new ArrayList<>();
        ArrayList<String> candidateDirs = new ArrayList<>(classDumpCandidateDirs(options.dir, configuredDir));
        String unifiedDumpDir = defaultCookieDumpDir(packageName);
        if (!candidateDirs.contains(unifiedDumpDir)) candidateDirs.add(unifiedDumpDir);
        for (String dir : candidateDirs) {
            addLooseDexDirSources(sources, seenPaths, dir, options, "class-dex-cache-dir", diagnostics);
        }
        if (sources.isEmpty()) {
            throw new IllegalStateException("class dex dump 目录没有可打开 dex 文件; diagnostics=" + diagnostics);
        }
        return openSources(sources);
    }

    public Object classDumpDirStatus() { return classDumpDirStatus(null); }
    public Object classDumpDirStatus(Object optionsObject) {
        DumpDirOptions options = DumpDirOptions.from(optionsObject, packageName);
        if (debugLogging) options.verbose = true;
        String configuredDir = DexClassLoadDumper.outputDir();
        options.dir = configuredDir;
        options.prefix = null;
        Object obj = unwrap(optionsObject);
        if (obj instanceof Scriptable) {
            Scriptable s = (Scriptable) obj;
            String dir = asString(get(s, "dir"));
            if (dir == null) dir = asString(get(s, "path"));
            if (dir == null) dir = asString(get(s, "outputDir"));
            if (dir != null) options.dir = dir;
        }
        ArrayList<Map<String, Object>> diagnostics = new ArrayList<>();
        ArrayList<DexSource> tmp = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<String> candidateDirs = new ArrayList<>(classDumpCandidateDirs(options.dir, configuredDir));
        String unifiedDumpDir = defaultCookieDumpDir(packageName);
        if (!candidateDirs.contains(unifiedDumpDir)) candidateDirs.add(unifiedDumpDir);
        for (String dir : candidateDirs) {
            addLooseDexDirSources(tmp, seen, dir, options, "class-dex-cache-dir", diagnostics);
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("configuredDir", configuredDir);
        out.put("candidateDirs", candidateDirs);
        out.put("sourceCount", tmp.size());
        out.put("diagnostics", diagnostics);
        out.put("memorySources", DexClassLoadDumper.sources());
        return js(out);
    }

    /**
     * Search all readable process memory for plain strings without assuming a dex header.
     * This is a diagnostic step for packers that decrypt strings/classes transiently but do
     * not leave a complete dex image in a readable mapping.
     */
    public Object scanMemoryStrings() { return scanMemoryStrings(null); }

    public Object scanMemoryStrings(Object optionsObject) {
        StringScanOptions options = StringScanOptions.from(optionsObject, packageName);
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("available", DexMemoryScanner.isAvailable());
        Throwable loadError = DexMemoryScanner.getLoadError();
        out.put("loadError", loadError == null ? null : String.valueOf(loadError));
        out.put("needles", options.needles);
        out.put("requireAll", options.requireAll);
        out.put("dumpWindows", options.dumpWindows);
        out.put("outputDir", options.outputDir);
        if (!DexMemoryScanner.isAvailable()) {
            out.put("ok", false);
            out.put("count", 0);
            out.put("hits", Collections.emptyList());
            return js(out);
        }
        List<Map<String, Object>> hits = DexMemoryScanner.scanStrings(
                options.outputDir,
                options.maxRegionBytes,
                options.maxHits,
                options.contextBytes,
                options.windowBytes,
                options.includeAnonymous,
                options.includeFileBacked,
                options.requireAll,
                options.dumpWindows,
                options.needles.toArray(new String[0])
        );
        out.put("ok", true);
        out.put("count", hits.size());
        out.put("hits", hits);
        out.put("maxRegionBytes", options.maxRegionBytes);
        out.put("maxHits", options.maxHits);
        out.put("contextBytes", options.contextBytes);
        out.put("windowBytes", options.windowBytes);
        out.put("includeAnonymous", options.includeAnonymous);
        out.put("includeFileBacked", options.includeFileBacked);
        return js(out);
    }


    private Map<String, Object> scanMemoryInternal(Object optionsObject, boolean defaultRegisterSources) {
        MemoryScanOptions options = MemoryScanOptions.from(optionsObject, packageName);
        boolean registerSources = options.registerSources != null ? options.registerSources : defaultRegisterSources;
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("available", DexMemoryScanner.isAvailable());
        Throwable loadError = DexMemoryScanner.getLoadError();
        out.put("loadError", loadError == null ? null : String.valueOf(loadError));
        if (!DexMemoryScanner.isAvailable()) {
            out.put("ok", false);
            out.put("count", 0);
            out.put("paths", Collections.emptyList());
            out.put("dumped", Collections.emptyList());
            out.put("candidates", Collections.emptyList());
            out.put("sources", Collections.emptyList());
            return out;
        }
        List<Map<String, Object>> dumped = DexMemoryScanner.scanAndDump(
                options.outputDir,
                options.maxRegionBytes,
                options.maxDumpBytes,
                options.maxDumpCount,
                options.includeAnonymous,
                options.includeFileBacked,
                options.relaxed,
                options.requireAsciiContains.toArray(new String[0]),
                options.requireAllAsciiContains
        );
        if (registerSources) {
            for (Map<String, Object> map : dumped) {
                Object path = map.get("path");
                if (path != null) DexRuntimeRegistry.registerPath(defaultLoader, String.valueOf(path), "memory-scan");
            }
        }
        out.put("ok", true);
        out.put("count", dumped.size());
        out.put("paths", pathsFromRows(dumped));
        out.put("dumped", dumped);
        out.put("candidates", dumped);
        out.put("sources", dumped);
        out.put("outputDir", options.outputDir);
        out.put("maxRegionBytes", options.maxRegionBytes);
        out.put("maxDumpBytes", options.maxDumpBytes);
        out.put("maxDumpCount", options.maxDumpCount);
        out.put("includeAnonymous", options.includeAnonymous);
        out.put("includeFileBacked", options.includeFileBacked);
        out.put("relaxed", options.relaxed);
        out.put("requireAsciiContains", options.requireAsciiContains);
        out.put("requireAllAsciiContains", options.requireAllAsciiContains);
        out.put("registerSources", registerSources);
        return out;
    }


    private Map<String, Object> scanMemoryRawInternal(Object optionsObject, boolean defaultRegisterSources) {
        MemoryScanOptions options = MemoryScanOptions.from(optionsObject, packageName);
        boolean registerSources = options.registerSources != null ? options.registerSources : defaultRegisterSources;
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("available", DexMemoryScanner.isAvailable());
        Throwable loadError = DexMemoryScanner.getLoadError();
        out.put("loadError", loadError == null ? null : String.valueOf(loadError));
        out.put("rawSalvage", true);
        if (!DexMemoryScanner.isAvailable()) {
            out.put("ok", false);
            out.put("count", 0);
            out.put("paths", Collections.emptyList());
            out.put("dumped", Collections.emptyList());
            out.put("candidates", Collections.emptyList());
            out.put("sources", Collections.emptyList());
            return out;
        }
        List<Map<String, Object>> dumped = DexMemoryScanner.scanAndDumpRaw(
                options.outputDir,
                options.maxRegionBytes,
                options.maxDumpBytes,
                options.maxDumpCount,
                options.includeAnonymous,
                options.includeFileBacked,
                options.includeInvalidRaw,
                options.requireAsciiContains.toArray(new String[0]),
                options.requireAllAsciiContains,
                options.rawWindowBytes
        );
        if (registerSources) {
            for (Map<String, Object> map : dumped) {
                Object path = map.get("path");
                if (path != null) DexRuntimeRegistry.registerPath(defaultLoader, String.valueOf(path), "memory-raw-salvage");
            }
        }
        out.put("ok", true);
        out.put("count", dumped.size());
        out.put("paths", pathsFromRows(dumped));
        out.put("dumped", dumped);
        out.put("candidates", dumped);
        out.put("sources", dumped);
        out.put("outputDir", options.outputDir);
        out.put("maxRegionBytes", options.maxRegionBytes);
        out.put("maxDumpBytes", options.maxDumpBytes);
        out.put("maxDumpCount", options.maxDumpCount);
        out.put("rawWindowBytes", options.rawWindowBytes);
        out.put("includeAnonymous", options.includeAnonymous);
        out.put("includeFileBacked", options.includeFileBacked);
        out.put("includeInvalidRaw", options.includeInvalidRaw);
        out.put("requireAsciiContains", options.requireAsciiContains);
        out.put("requireAllAsciiContains", options.requireAllAsciiContains);
        out.put("registerSources", registerSources);
        return out;
    }


    /** Delete old memory-dex dump files so stale/duplicate invalid dumps do not affect a new scan. */
    public Object clearDumpDir() { return clearDumpDir(null); }

    public Object clearDumpDir(Object optionsObject) {
        DumpDirOptions options = DumpDirOptions.from(optionsObject, packageName);
        if (debugLogging) options.verbose = true;
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("dir", options.dir);
        out.put("repairedDir", options.repairedDir);
        int deleted = 0;
        int failed = 0;
        for (File f : dumpFiles(options, false)) {
            if (f.delete()) deleted++; else failed++;
        }
        File repairedRoot = new File(options.repairedDir == null ? options.dir + "_repaired" : options.repairedDir);
        File[] repairedFiles = repairedRoot.listFiles();
        if (repairedFiles != null) {
            for (File f : repairedFiles) {
                if (f != null && f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".dex")) {
                    if (f.delete()) deleted++; else failed++;
                }
            }
        }
        out.put("deleted", deleted);
        out.put("failed", failed);
        return js(out);
    }

    /** Returns dex-like files currently present in a dump directory. */
    public Object dumpSources() { return dumpSources(null); }

    public Object dumpSources(Object optionsObject) {
        DumpDirOptions options = DumpDirOptions.from(optionsObject, packageName);
        if (debugLogging) options.verbose = true;
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (File file : dumpFiles(options)) {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("path", file.getAbsolutePath());
            map.put("entry", null);
            map.put("type", sourceType(file));
            map.put("origin", "dump-dir");
            map.put("fileSize", file.length());
            DexPreflight preflight = preflightDexFile(file);
            map.put("preflightOk", preflight.ok);
            map.put("preflight", preflight.reason);
            out.add(map);
        }
        return js(out);
    }

    public Object repairDex(String path) { return repairDex(path, null); }

    public Object repairDex(String path, Object optionsObject) {
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("path 不能为空");
        DumpDirOptions options = DumpDirOptions.from(optionsObject, packageName);
        if (debugLogging) options.verbose = true;
        return js(repairDexFile(new File(path.trim()), options));
    }

    public Object repairDumpDir() { return repairDumpDir(null); }

    public Object repairDumpDir(Object optionsObject) {
        DumpDirOptions options = DumpDirOptions.from(optionsObject, packageName);
        if (debugLogging) options.verbose = true;
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (File file : dumpFiles(options)) out.add(repairDexFile(file, options));
        return js(out);
    }

    /** Returns dumped memory dex sources from the last dex.scanMemory(...) call. */
    public Object memorySources() {
        return js(DexMemoryScanner.getLastSources());
    }

    /** Opens dumped memory dex images from the last dex.scanMemory(...) call. */
    public DexFileView fromMemory() throws Exception {
        List<DexSource> sources = memoryDexSources();
        if (sources.isEmpty()) {
            throw new IllegalStateException("尚未扫描到内存 dex。请先调用 dex.scanMemory({...})，再调用 dex.fromMemory()");
        }
        return openSources(sources);
    }

    /** Open all dex/apk/jar/zip sources captured from runtime class loaders in this process. */
    public DexFileView fromRuntime() throws Exception {
        ArrayList<DexSource> sources = new ArrayList<>();
        sources.addAll(DexRuntimeRegistry.allSources());
        sources.addAll(memoryDexSources());
        if (sources.isEmpty()) {
            throw new IllegalStateException("RuntimeRegistry 尚未捕获到动态 dex 来源，也没有内存 dex dump。请先调用 dex.watchLoaders()/等待动态加载，或调用 dex.scanMemory({...})。");
        }
        return openSources(sources);
    }

    private List<DexSource> memoryDexSources() {
        ArrayList<DexSource> sources = new ArrayList<>();
        for (Map<String, Object> map : DexMemoryScanner.getLastSources()) {
            if (map == null) continue;
            Object pathObj = map.get("path");
            if (pathObj == null) continue;
            File file = new File(String.valueOf(pathObj));
            if (!file.isFile()) continue;
            String origin = String.valueOf(map.containsKey("origin") ? map.get("origin") : "memory-scan");
            sources.add(new DexSource(file.getAbsolutePath(), null, sourceType(file), defaultLoader, origin));
        }
        return sources;
    }

    /** Returns captured runtime dex sources for diagnostics. */
    public Object runtimeSources() {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (DexSource source : DexRuntimeRegistry.allSources()) out.add(source.toMap());
        return js(out);
    }

    /** Returns captured runtime class loaders for diagnostics. */
    public Object runtimeLoaders() {
        return js(DexRuntimeRegistry.loaders());
    }

    /** Explicitly install dynamic class-loader capture hooks. The constructor already calls this; this method is a JS-visible no-op/diagnostic helper. */
    public boolean watchLoaders() {
        DexRuntimeRegistry.install(module, packageName, processName, defaultLoader);
        return true;
    }

    /** Manual registration helper for scripts that discover a custom loader/path themselves. */
    public Object registerLoader(Object loaderObject, String path) {
        Object obj = unwrap(loaderObject);
        if (!(obj instanceof ClassLoader)) {
            throw new IllegalArgumentException("registerLoader(loader, path) 需要显式传入 ClassLoader，不能静默使用默认 loader");
        }
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("registerLoader(loader, path) 的 path 不能为空");
        }
        ClassLoader loader = (ClassLoader) obj;
        boolean registered = DexRuntimeRegistry.registerPath(loader, path, "manual-js-register");
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("ok", registered);
        out.put("registered", registered);
        out.put("loaderId", System.identityHashCode(loader));
        out.put("loader", String.valueOf(loader));
        out.put("path", path);
        return js(out);
    }

    /** Configure safety limits used by dex.fromFile/fromLoader/fromRuntime/fromMemory and smali rendering. */
    public Object setLimits(Object optionsObject) {
        Object obj = unwrap(optionsObject);
        if (obj instanceof Scriptable) {
            Scriptable s = (Scriptable) obj;
            maxDexBytes = clampLong(asLong(get(s, "maxDexBytes"), maxDexBytes), 1024L * 1024L, HARD_MAX_DEX_BYTES);
            maxClasses = clampInt(asInt(get(s, "maxClasses"), maxClasses), 1_000, HARD_MAX_CLASSES);
            maxMethods = clampInt(asInt(get(s, "maxMethods"), maxMethods), 10_000, HARD_MAX_METHODS);
            maxSmaliChars = clampInt(asInt(get(s, "maxSmaliChars"), maxSmaliChars), 10_000, HARD_MAX_SMALI_CHARS);
        }
        return js(limitsEnvelope(true));
    }

    /** Return current Dex API safety limits. */
    public Object limits() {
        return js(limitsEnvelope(true));
    }

    private static LinkedHashMap<String, Object> limitsMap() {
        LinkedHashMap<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxDexBytes", maxDexBytes);
        limits.put("maxClasses", maxClasses);
        limits.put("maxMethods", maxMethods);
        limits.put("maxSmaliChars", maxSmaliChars);
        limits.put("defaultMaxDexBytes", DEFAULT_MAX_DEX_BYTES);
        return limits;
    }

    private static LinkedHashMap<String, Object> limitsEnvelope(boolean ok) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("limits", limitsMap());
        return out;
    }

    /** Reserved API. Rhino byte-array conversion differs between scripts, so this is intentionally explicit. */
    public DexFileView fromBytes(Object bytes, String name) {
        throw new UnsupportedOperationException("dex.fromBytes(bytes, name) 已预留，第一版请使用 dex.fromFile(path) 或 dex.fromLoader(loader)");
    }

    public Object findMethods(Object queryObject) throws Exception {
        Query query = Query.from(queryObject);
        if (debugLogging) query.verbose = true;
        DexFileView fileView = sourceForQuery(query);
        return js(fileView.findMethodsRaw(query).toListRaw());
    }

    private DexFileView sourceForQuery(Query query) throws Exception {
        if (query != null && query.file != null) return query.file;
        if (query != null && query.paths != null && !query.paths.isEmpty()) {
            ArrayList<DexSource> sources = new ArrayList<>();
            for (String rawPath : query.paths) {
                String path = rawPath == null ? null : rawPath.trim();
                if (path == null || path.isEmpty()) continue;
                File file = new File(path);
                if (!file.isFile()) throw new IllegalArgumentException("dex 文件不存在: " + file.getAbsolutePath());
                sources.add(new DexSource(file.getAbsolutePath(), null, sourceType(file), null, "findMethods.path"));
            }
            if (sources.isEmpty()) throw new IllegalArgumentException("paths 为空或没有有效 dex/apk 文件");
            return openSources(sources);
        }
        if (query != null && query.path != null && !query.path.trim().isEmpty()) {
            return fromFile(query.path.trim());
        }
        return fromLoader(query == null ? null : query.loader);
    }

    public Object findMethod(Object queryObject) throws Exception {
        Query query = Query.from(queryObject);
        if (debugLogging) query.verbose = true;
        DexFileView fileView = sourceForQuery(query);
        DexMethodHit hit = fileView.findMethodsRaw(query).best();
        return hit == null ? null : js(hit.toMapRaw());
    }

    public DexFileView fingerprint(Object queryObject) throws Exception {
        Query query = Query.from(queryObject);
        return query.file != null ? query.file : fromLoader(query.loader == null ? defaultLoader : query.loader);
    }

    public Object findSource(Object methodOrClassOrLoader) {
        Object value = unwrap(methodOrClassOrLoader);
        ClassLoader loader = defaultLoader;
        if (value instanceof ClassLoader) loader = (ClassLoader) value;
        if (value instanceof Class<?>) loader = ((Class<?>) value).getClassLoader();
        if (value instanceof Executable) loader = ((Executable) value).getDeclaringClass().getClassLoader();
        List<DexSource> sources = DexSourceResolver.fromLoader(loader == null ? defaultLoader : loader);
        ArrayList<Map<String, Object>> list = new ArrayList<>();
        for (DexSource source : sources) list.add(source.toMap());
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("loader", String.valueOf(loader));
        out.put("sources", list);
        out.put("paths", pathsFromRows(list));
        return js(out);
    }

    private DexFileView openSources(List<DexSource> sources) throws Exception {
        ArrayList<DexUnit> units = new ArrayList<>();
        ArrayList<Map<String, Object>> skipped = new ArrayList<>();
        int classCount = 0;
        for (DexSource source : sources) {
            File file = new File(source.path);
            if (!file.isFile()) continue;
            try {
                if (file.length() > maxDexBytes && "dex".equals(source.type)) {
                    throw new IllegalStateException("dex 文件过大，拒绝读取: " + file.getAbsolutePath()
                            + " size=" + file.length() + " maxDexBytes=" + maxDexBytes);
                }
                if (source.entry != null && !source.entry.trim().isEmpty()) {
                    MultiDexContainer<? extends DexBackedDexFile> container = DexFileFactory.loadDexContainer(file, Opcodes.getDefault());
                    MultiDexContainer.DexEntry<? extends DexBackedDexFile> entry = container.getEntry(source.entry);
                    if (entry != null) {
                        DexBackedDexFile dexFile = entry.getDexFile();
                        assertDexIterable(source, dexFile);
                        units.add(new DexUnit(source, dexFile));
                        classCount += safeClassCount(dexFile);
                    }
                } else if ("dex".equals(source.type)) {
                    DexPreflight preflight = preflightDexFile(file);
                    if (!preflight.ok) throw new IllegalStateException(preflight.reason);
                    DexBackedDexFile dexFile = DexFileFactory.loadDexFile(file, Opcodes.getDefault());
                    assertDexIterable(source, dexFile);
                    units.add(new DexUnit(source, dexFile));
                    classCount += safeClassCount(dexFile);
                } else {
                    MultiDexContainer<? extends DexBackedDexFile> container = DexFileFactory.loadDexContainer(file, Opcodes.getDefault());
                    for (String entryName : dexEntryNames(file, container)) {
                        if (!entryName.endsWith(".dex")) continue;
                        try {
                            MultiDexContainer.DexEntry<? extends DexBackedDexFile> entry = container.getEntry(entryName);
                            if (entry == null) continue;
                            DexSource childSource = new DexSource(source.path, entryName, source.type, source.loader, source.origin);
                            DexBackedDexFile dexFile = entry.getDexFile();
                            assertDexIterable(childSource, dexFile);
                            units.add(new DexUnit(childSource, dexFile));
                            classCount += safeClassCount(dexFile);
                            if (classCount > maxClasses) {
                                throw new IllegalStateException("dex class 数量超过限制: " + maxClasses);
                            }
                        } catch (Throwable entryError) {
                            DexSource childSource = new DexSource(source.path, entryName, source.type, source.loader, source.origin);
                            skipped.add(skipMap(childSource, "open-entry", entryError));
                            Log.w(TAG, "skip dex entry: " + childSource.path + "!/" + entryName, entryError);
                        }
                    }
                }
                if (classCount > maxClasses) {
                    throw new IllegalStateException("dex class 数量超过限制: " + maxClasses);
                }
            } catch (Throwable t) {
                skipped.add(skipMap(source, "open-source", t));
                Log.w(TAG, "skip dex source: " + source.path + (source.entry == null ? "" : "!/" + source.entry), t);
            }
        }
        if (units.isEmpty()) {
            throw new IllegalStateException("未打开任何 dex 来源; skipped=" + skipped);
        }
        return new DexFileView(units, defaultLoader, skipped);
    }

    private static int safeClassCount(DexFile dexFile) {
        try {
            return dexFile.getClasses().size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void assertDexIterable(DexSource source, DexFile dexFile) {
        try {
            Iterable<? extends ClassDef> classes = dexFile.getClasses();
            if (classes != null) {
                Iterator<? extends ClassDef> it = classes.iterator();
                if (it != null && it.hasNext()) {
                    ClassDef cls = it.next();
                    if (cls != null) cls.getType();
                }
            }
        } catch (Throwable t) {
            throw new IllegalStateException("dex 结构初步遍历失败: " + source.path
                    + (source.entry == null ? "" : "!/" + source.entry) + " error=" + brief(t), t);
        }
    }

    private static Map<String, Object> skipMap(DexSource source, String stage, Throwable t) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("path", source == null ? null : source.path);
        map.put("entry", source == null ? null : source.entry);
        map.put("type", source == null ? null : source.type);
        map.put("origin", source == null ? null : source.origin);
        map.put("stage", stage);
        map.put("error", brief(t));
        return map;
    }

    private static String defaultDumpDir(String packageName) {
        String pkg = packageName == null || packageName.trim().isEmpty() ? "unknown" : packageName.trim();
        return "/data/user/0/" + pkg + "/code_cache/xhh_memory_dex";
    }

    private static String defaultCookieDumpDir(String packageName) {
        String pkg = packageName == null || packageName.trim().isEmpty() ? "unknown" : packageName.trim();
        return "/data/user/0/" + pkg + "/code_cache/xhh_dumpdex";
    }

    private static String legacyClassDexCookieDir(String packageName) {
        String pkg = packageName == null || packageName.trim().isEmpty() ? "unknown" : packageName.trim();
        return "/data/user/0/" + pkg + "/code_cache/xhh_class_dex_cookie";
    }

    private static boolean isLegacyCookieDir(String path) {
        if (path == null) return false;
        String normalized = path.replace('\\', '/');
        return normalized.endsWith("/xhh_class_dex_cookie")
                || normalized.endsWith("/xhh_memory_dex_cookie");
    }

    private static String normalizeCookieOutputDir(String path, String packageName) {
        if (path == null || path.trim().isEmpty() || isLegacyCookieDir(path)) {
            return defaultCookieDumpDir(packageName);
        }
        return path.trim();
    }

    private static Map<String, Object> cleanupLegacyCookieDirs(String packageName) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        ArrayList<String> deleted = new ArrayList<>();
        ArrayList<String> failed = new ArrayList<>();
        String pkg = packageName == null || packageName.trim().isEmpty() ? "unknown" : packageName.trim();
        String[] dirs = new String[] {
                legacyClassDexCookieDir(pkg),
                "/data/user/0/" + pkg + "/code_cache/xhh_memory_dex_cookie"
        };
        for (String dir : dirs) {
            File file = new File(dir);
            if (!file.exists()) continue;
            try {
                if (deleteRecursive(file)) deleted.add(dir);
                else failed.add(dir);
            } catch (Throwable t) {
                failed.add(dir + ": " + t);
            }
        }
        out.put("deleted", deleted);
        out.put("failed", failed);
        return out;
    }

    private static boolean deleteRecursive(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) return false;
                }
            }
        }
        return file.delete() || !file.exists();
    }

    private static List<String> classDumpCandidateDirs(String requestedDir, String configuredDir) {
        LinkedHashSet<String> dirs = new LinkedHashSet<>();
        if (requestedDir != null && !requestedDir.trim().isEmpty() && !isLegacyCookieDir(requestedDir)) dirs.add(requestedDir.trim());
        if (configuredDir != null && !configuredDir.trim().isEmpty() && !isLegacyCookieDir(configuredDir)) dirs.add(configuredDir.trim());
        return new ArrayList<>(dirs);
    }

    private static void addLooseDexDirSources(List<DexSource> sources,
                                              Set<String> seenPaths,
                                              String dir,
                                              DumpDirOptions options,
                                              String origin,
                                              List<Map<String, Object>> diagnostics) {
        LinkedHashMap<String, Object> diag = new LinkedHashMap<>();
        diag.put("dir", dir);
        int totalFiles = 0;
        int dexSuffix = 0;
        int accepted = 0;
        int rejectedSize = 0;
        int rejectedPrefix = 0;
        int unreadable = 0;
        try {
            File root = new File(dir);
            diag.put("exists", root.exists());
            diag.put("isDirectory", root.isDirectory());
            diag.put("isFile", root.isFile());
            diag.put("canRead", root.canRead());
            diag.put("absolutePath", root.getAbsolutePath());
            ArrayList<String> sample = new ArrayList<>();
            if (root.isFile()) {
                totalFiles = 1;
                if (acceptLooseDexFile(root, options)) {
                    accepted += addSourceIfNew(sources, seenPaths, root, origin);
                } else {
                    if (!root.canRead()) unreadable++;
                    if (!root.getName().toLowerCase(Locale.ROOT).endsWith(".dex")) {
                        // not counted as dex suffix
                    } else {
                        dexSuffix++;
                        long len = root.length();
                        if (len < options.minBytes || len > options.maxBytes) rejectedSize++;
                        else rejectedPrefix++;
                    }
                }
                sample.add(root.getName() + ":" + root.length());
            } else {
                File[] files = root.listFiles();
                diag.put("listFilesNull", files == null);
                if (files != null) {
                    for (File f : files) {
                        if (f == null || !f.isFile()) continue;
                        totalFiles++;
                        if (sample.size() < 12) sample.add(f.getName() + ":" + f.length());
                        String lower = f.getName().toLowerCase(Locale.ROOT);
                        if (lower.endsWith(".dex")) dexSuffix++;
                        if (!f.canRead()) unreadable++;
                        if (acceptLooseDexFile(f, options)) {
                            accepted += addSourceIfNew(sources, seenPaths, f, origin);
                        } else if (lower.endsWith(".dex")) {
                            long len = f.length();
                            if (len < options.minBytes || len > options.maxBytes) rejectedSize++;
                            else rejectedPrefix++;
                        }
                    }
                }
            }
            diag.put("totalFiles", totalFiles);
            diag.put("dexSuffix", dexSuffix);
            diag.put("accepted", accepted);
            diag.put("rejectedSize", rejectedSize);
            diag.put("rejectedPrefix", rejectedPrefix);
            diag.put("unreadable", unreadable);
            diag.put("sample", sample);
        } catch (Throwable t) {
            diag.put("error", brief(t));
        }
        if (diagnostics != null) diagnostics.add(diag);
    }

    private static int addSourceIfNew(List<DexSource> sources, Set<String> seenPaths, File file, String origin) {
        if (file == null || !file.isFile()) return 0;
        String path = file.getAbsolutePath();
        if (seenPaths != null && !seenPaths.add(path)) return 0;
        sources.add(new DexSource(path, null, sourceType(file), null, origin));
        return 1;
    }

    private static boolean acceptLooseDexFile(File file, DumpDirOptions options) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".dex")) return false;
        long len = file.length();
        if (len < options.minBytes || len > options.maxBytes) return false;
        if (options.prefix != null && !options.prefix.isEmpty() && !name.startsWith(options.prefix)) return false;
        return file.canRead();
    }

    private static List<File> dumpFiles(DumpDirOptions options) {
        return dumpFiles(options, options.dedupe);
    }

    private static List<File> dumpFiles(DumpDirOptions options, boolean dedupe) {
        ArrayList<File> out = new ArrayList<>();
        File root = new File(options.dir);
        if (root.isFile()) {
            if (isDexLikeFile(root, options)) out.add(root);
            return out;
        }
        File[] files = root.listFiles();
        if (files == null) return out;
        for (File file : files) {
            if (file != null && file.isFile() && isDexLikeFile(file, options)) out.add(file);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        if (dedupe && out.size() > 1) {
            LinkedHashMap<String, File> unique = new LinkedHashMap<>();
            for (File f : out) {
                String fp = quickFileFingerprint(f);
                if (!unique.containsKey(fp)) unique.put(fp, f);
            }
            out = new ArrayList<>(unique.values());
        }
        if (options.limit > 0 && out.size() > options.limit) {
            return new ArrayList<>(out.subList(0, options.limit));
        }
        return out;
    }

    private static boolean rawMatched(List<String> matches, DumpDirOptions options) {
        if (options == null || options.contains == null || options.contains.isEmpty()) return !matches.isEmpty();
        if (!options.matchAllContains) return !matches.isEmpty();
        return matches != null && matches.size() >= options.contains.size();
    }

    private static String quickFileFingerprint(File file) {
        if (file == null || !file.isFile()) return "missing";
        long len = file.length();
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] meta = (file.getName().replaceAll("^memdex_\\d+_[0-9a-fA-F]+_", "") + ":" + len).getBytes("UTF-8");
            digest.update(meta);
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                byte[] buf = new byte[256 * 1024];
                long[] positions = new long[] {0L, Math.max(0L, len / 2L - buf.length / 2L), Math.max(0L, len - buf.length)};
                for (long pos : positions) {
                    if (pos >= len) continue;
                    raf.seek(pos);
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, len - pos));
                    if (n > 0) digest.update(buf, 0, n);
                }
            }
            byte[] d = digest.digest();
            StringBuilder sb = new StringBuilder();
            sb.append(len).append(':');
            for (int i = 0; i < Math.min(8, d.length); i++) sb.append(String.format(Locale.ROOT, "%02x", d[i] & 0xff));
            return sb.toString();
        } catch (Throwable ignored) {
            return len + ":" + file.getName();
        }
    }

    private static boolean isDexLikeFile(File file, DumpDirOptions options) {
        String name = file.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".dex")) return false;
        if (options.prefix != null && !name.startsWith(options.prefix)) return false;
        long len = file.length();
        if (len < options.minBytes || len > options.maxBytes) return false;
        return true;
    }

    private static List<String> rawContains(File file, List<String> needles, int maxBytes) {
        ArrayList<String> out = new ArrayList<>();
        if (needles == null || needles.isEmpty() || file == null || !file.isFile()) return out;

        // Streaming raw string scan. Older implementations allocated up to rawScanBytes
        // for every cookie dex (often 64-128MB).  When the script scanned dozens of
        // dumped dex files inside Application.attach, that could push the target process
        // into OOM even before dexlib2 method matching started.
        ArrayList<byte[]> needleBytes = new ArrayList<>();
        ArrayList<String> needleStrings = new ArrayList<>();
        int maxNeedle = 0;
        for (String needle : needles) {
            if (needle == null || needle.isEmpty() || out.contains(needle)) continue;
            try {
                byte[] nd = needle.getBytes("UTF-8");
                if (nd.length == 0) continue;
                needleStrings.add(needle);
                needleBytes.add(nd);
                if (nd.length > maxNeedle) maxNeedle = nd.length;
            } catch (Throwable ignored) {
            }
        }
        if (needleBytes.isEmpty()) return out;

        long max = Math.max(4096L, maxBytes <= 0
                ? Math.min(file.length(), 64L * 1024L * 1024L)
                : Math.min(file.length(), (long) maxBytes));
        int overlap = Math.min(Math.max(0, maxNeedle - 1), 64 * 1024);
        int chunk = 1024 * 1024;
        byte[] buffer = new byte[chunk + overlap];
        int carry = 0;
        long readTotal = 0L;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            while (readTotal < max && out.size() < needleBytes.size()) {
                int want = (int) Math.min(chunk, max - readTotal);
                int n = in.read(buffer, carry, want);
                if (n <= 0) break;
                int len = carry + n;
                readTotal += n;
                for (int i = 0; i < needleBytes.size(); i++) {
                    String needle = needleStrings.get(i);
                    if (out.contains(needle)) continue;
                    byte[] nd = needleBytes.get(i);
                    if (indexOf(buffer, len, nd) >= 0) out.add(needle);
                }
                carry = Math.min(overlap, len);
                if (carry > 0) System.arraycopy(buffer, len - carry, buffer, 0, carry);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static int indexOf(byte[] haystack, int length, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || length < needle.length) return -1;
        outer:
        for (int i = 0; i <= length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static Map<String, Object> repairDexFile(File file, DumpDirOptions options) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("path", file == null ? null : file.getAbsolutePath());
        if (file == null || !file.isFile()) {
            out.put("ok", false);
            out.put("error", "文件不存在");
            return out;
        }
        out.put("originalSize", file.length());
        DexPreflight before = preflightDexFile(file);
        out.put("preflightBeforeOk", before.ok);
        out.put("preflightBefore", before.reason);
        ArrayList<String> actions = new ArrayList<>();
        try {
            byte[] data = readFileBytes(file, Math.min(options.maxBytes + Math.max(0, options.tailPaddingBytes), HARD_MAX_DEX_BYTES));
            if (data.length < 0x70 || !isDexMagic(data)) {
                out.put("ok", false);
                out.put("error", "不是 dex dump，无法修复");
                return out;
            }
            long headerFileSize = u32(data, 32);
            if (headerFileSize < 0x70 || headerFileSize > HARD_MAX_DEX_BYTES) {
                out.put("ok", false);
                out.put("error", "header file_size 异常: " + headerFileSize);
                return out;
            }
            int targetSize = data.length;
            if (headerFileSize < data.length && options.trimToHeaderSize) {
                targetSize = (int) headerFileSize;
                actions.add("trim-to-header-file-size");
            } else if (headerFileSize > data.length && options.padToHeaderSize) {
                targetSize = (int) headerFileSize;
                actions.add("pad-to-header-file-size");
            }
            if (!before.ok && options.tailPaddingBytes > 0) {
                targetSize = Math.max(targetSize, data.length + options.tailPaddingBytes);
                actions.add("append-tail-padding-" + options.tailPaddingBytes);
            }
            if (targetSize != data.length) {
                byte[] resized = new byte[targetSize];
                System.arraycopy(data, 0, resized, 0, Math.min(data.length, resized.length));
                data = resized;
            }
            if (options.updateFileSize && u32(data, 32) != data.length) {
                putU32(data, 32, data.length);
                actions.add("update-header-file-size");
            }
            if (options.recomputeSignature) {
                recomputeDexSignature(data);
                actions.add("recompute-sha1-signature");
            }
            if (options.recomputeChecksum) {
                recomputeDexChecksum(data);
                actions.add("recompute-adler32-checksum");
            }
            File outDir = new File(options.repairedDir == null ? file.getParentFile().getAbsolutePath() + "_repaired" : options.repairedDir);
            if (!outDir.exists() && !outDir.mkdirs()) throw new IllegalStateException("无法创建修复目录: " + outDir.getAbsolutePath());
            File repaired = new File(outDir, file.getName().replaceAll("\\.dex$", "") + ".repaired.dex");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(repaired)) {
                fos.write(data);
            }
            DexPreflight after = preflightDexFile(repaired);
            out.put("ok", after.ok);
            out.put("repairedPath", repaired.getAbsolutePath());
            out.put("repairedSize", repaired.length());
            out.put("preflightAfterOk", after.ok);
            out.put("preflightAfter", after.reason);
            out.put("actions", actions);
            if (!after.ok && before.ok) {
                out.put("note", "原始 dex 已通过预校验，修复输出未通过；建议直接打开原始文件");
            }
            return out;
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("error", brief(t));
            out.put("actions", actions);
            return out;
        }
    }

    private static byte[] readFileBytes(File file, long maxBytesAllowed) throws Exception {
        long len = file.length();
        if (len < 0 || len > maxBytesAllowed || len > Integer.MAX_VALUE) {
            throw new IllegalStateException("文件过大，拒绝一次性读取: " + file.getAbsolutePath() + " size=" + len + " max=" + maxBytesAllowed);
        }
        byte[] data = new byte[(int) len];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int total = 0;
            while (total < data.length) {
                int n = in.read(data, total, data.length - total);
                if (n <= 0) break;
                total += n;
            }
            if (total != data.length) throw new java.io.EOFException("读取 dex 文件不完整: " + total + "/" + data.length);
        }
        return data;
    }

    private static boolean isDexMagic(byte[] data) {
        return data != null && data.length >= 8
                && data[0] == 'd' && data[1] == 'e' && data[2] == 'x' && data[3] == '\n'
                && data[4] == '0' && (data[5] == '3' || data[5] == '4') && data[7] == 0;
    }

    private static void putU32(byte[] data, int off, long value) {
        data[off] = (byte) (value & 0xff);
        data[off + 1] = (byte) ((value >> 8) & 0xff);
        data[off + 2] = (byte) ((value >> 16) & 0xff);
        data[off + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static void recomputeDexSignature(byte[] data) throws Exception {
        if (data.length < 32) return;
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
        digest.update(data, 32, data.length - 32);
        byte[] sig = digest.digest();
        System.arraycopy(sig, 0, data, 12, Math.min(20, sig.length));
    }

    private static void recomputeDexChecksum(byte[] data) {
        if (data.length < 12) return;
        java.util.zip.Adler32 adler32 = new java.util.zip.Adler32();
        adler32.update(data, 12, data.length - 12);
        putU32(data, 8, adler32.getValue());
    }

    private static String brief(Throwable t) {
        if (t == null) return "null";
        String msg = t.getMessage();
        return t.getClass().getName() + (msg == null || msg.isEmpty() ? "" : ": " + msg);
    }

    static final class DexPreflight {
        final boolean ok;
        final String reason;
        DexPreflight(boolean ok, String reason) { this.ok = ok; this.reason = reason; }
        static DexPreflight ok() { return new DexPreflight(true, "ok"); }
        static DexPreflight fail(String reason) { return new DexPreflight(false, reason); }
    }

    private static DexPreflight preflightDexFile(File file) {
        long diskLength = file.length();
        if (diskLength < 0x70) return DexPreflight.fail("dex 太小: " + diskLength);
        if (diskLength > maxDexBytes) return DexPreflight.fail("dex 文件过大: size=" + diskLength + " maxDexBytes=" + maxDexBytes);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[0x70];
            raf.readFully(header);
            if (!(header[0] == 'd' && header[1] == 'e' && header[2] == 'x' && header[3] == '\n')) return DexPreflight.fail("不是 dex magic");
            if (!(header[4] == '0' && (header[5] == '3' || header[5] == '4') && header[7] == 0)) return DexPreflight.fail("不支持的 dex version");
            long fileSize = u32(header, 32);
            long headerSize = u32(header, 36);
            long endianTag = u32(header, 40);
            long mapOff = u32(header, 52);
            long stringIdsSize = u32(header, 56);
            long stringIdsOff = u32(header, 60);
            long typeIdsSize = u32(header, 64);
            long typeIdsOff = u32(header, 68);
            long protoIdsSize = u32(header, 72);
            long protoIdsOff = u32(header, 76);
            long fieldIdsSize = u32(header, 80);
            long fieldIdsOff = u32(header, 84);
            long methodIdsSize = u32(header, 88);
            long methodIdsOff = u32(header, 92);
            long classDefsSize = u32(header, 96);
            long classDefsOff = u32(header, 100);
            long dataSize = u32(header, 104);
            long dataOff = u32(header, 108);
            if (headerSize != 0x70) return DexPreflight.fail("header_size 异常: " + headerSize);
            if (endianTag != 0x12345678L) return DexPreflight.fail("endian_tag 异常: " + endianTag);
            if (fileSize < 0x70 || fileSize > diskLength) return DexPreflight.fail("file_size 越界: header=" + fileSize + " disk=" + diskLength);
            if (!validTable(fileSize, stringIdsSize, stringIdsOff, 4)) return DexPreflight.fail("string_ids 越界");
            if (!validTable(fileSize, typeIdsSize, typeIdsOff, 4)) return DexPreflight.fail("type_ids 越界");
            if (!validTable(fileSize, protoIdsSize, protoIdsOff, 12)) return DexPreflight.fail("proto_ids 越界");
            if (!validTable(fileSize, fieldIdsSize, fieldIdsOff, 8)) return DexPreflight.fail("field_ids 越界");
            if (!validTable(fileSize, methodIdsSize, methodIdsOff, 8)) return DexPreflight.fail("method_ids 越界");
            if (!validTable(fileSize, classDefsSize, classDefsOff, 32)) return DexPreflight.fail("class_defs 越界");
            if (dataSize > 0 && (dataOff == 0 || dataOff >= fileSize || dataOff + dataSize > fileSize)) return DexPreflight.fail("data 区越界");
            if (mapOff != 0) {
                DexPreflight mapCheck = validateMapList(raf, fileSize, mapOff);
                if (!mapCheck.ok) return mapCheck;
            }
            DexPreflight indexCheck = validateIndexTables(raf, fileSize, stringIdsSize, stringIdsOff, typeIdsSize, typeIdsOff,
                    protoIdsSize, protoIdsOff, fieldIdsSize, fieldIdsOff, methodIdsSize, methodIdsOff, classDefsSize, classDefsOff);
            if (!indexCheck.ok) return indexCheck;
            DexPreflight stringCheck = validateStringData(raf, fileSize, stringIdsSize, stringIdsOff);
            if (!stringCheck.ok) return stringCheck;
            return DexPreflight.ok();
        } catch (Throwable t) {
            return DexPreflight.fail("dex 预校验异常: " + brief(t));
        }
    }

    private static DexPreflight validateMapList(RandomAccessFile raf, long fileSize, long mapOff) throws Exception {
        if (mapOff <= 0 || mapOff + 4 > fileSize) return DexPreflight.fail("map_off 越界: " + mapOff);
        raf.seek(mapOff);
        long mapSize = readU32(raf);
        if (mapSize <= 0 || mapSize > 4096) return DexPreflight.fail("map_size 异常: " + mapSize);
        if (mapOff + 4 + mapSize * 12L > fileSize) return DexPreflight.fail("map_list 越界: size=" + mapSize);
        for (long i = 0; i < mapSize; i++) {
            int type = readU16(raf);
            readU16(raf); // unused
            long size = readU32(raf);
            long off = readU32(raf);
            if (size == 0) continue;
            int itemSize = fixedMapItemSize(type);
            if (type == 0x0000) {
                if (off != 0) return DexPreflight.fail("header_item offset 异常 off=" + off);
            } else if (off <= 0 || off >= fileSize) {
                return DexPreflight.fail("map_item offset 越界 type=0x" + Integer.toHexString(type) + " off=" + off);
            }
            if (itemSize > 0 && off + size * (long) itemSize > fileSize) {
                return DexPreflight.fail("map_item 越界 type=0x" + Integer.toHexString(type) + " off=" + off + " size=" + size);
            }
        }
        return DexPreflight.ok();
    }

    private static int fixedMapItemSize(int type) {
        switch (type) {
            case 0x0000: return 0x70; // header_item
            case 0x0001: return 4;    // string_id_item
            case 0x0002: return 4;    // type_id_item
            case 0x0003: return 12;   // proto_id_item
            case 0x0004: return 8;    // field_id_item
            case 0x0005: return 8;    // method_id_item
            case 0x0006: return 32;   // class_def_item
            case 0x1000: return 4;    // map_list size field, conservative
            case 0x1001: return 1;    // type_list variable but at least starts at offset
            case 0x1002: return 1;    // annotation_set_ref_list
            case 0x1003: return 1;    // annotation_set_item
            case 0x1004: return 1;    // class_data_item
            case 0x1005: return 1;    // code_item
            case 0x1006: return 1;    // string_data_item
            case 0x2000: return 1;    // debug_info_item
            case 0x2001: return 1;    // annotation_item
            case 0x2002: return 1;    // encoded_array_item
            case 0x2003: return 1;    // annotations_directory_item
            default: return 1;
        }
    }

    private static DexPreflight validateIndexTables(RandomAccessFile raf, long fileSize,
                                                     long stringIdsSize, long stringIdsOff,
                                                     long typeIdsSize, long typeIdsOff,
                                                     long protoIdsSize, long protoIdsOff,
                                                     long fieldIdsSize, long fieldIdsOff,
                                                     long methodIdsSize, long methodIdsOff,
                                                     long classDefsSize, long classDefsOff) throws Exception {
        for (long i = 0; i < typeIdsSize; i++) {
            long descriptorIdx = readU32At(raf, typeIdsOff + i * 4L);
            if (descriptorIdx >= stringIdsSize) return DexPreflight.fail("type_id.descriptor_idx 越界 i=" + i);
        }
        for (long i = 0; i < protoIdsSize; i++) {
            long base = protoIdsOff + i * 12L;
            long shortyIdx = readU32At(raf, base);
            long returnTypeIdx = readU32At(raf, base + 4);
            long parametersOff = readU32At(raf, base + 8);
            if (shortyIdx >= stringIdsSize) return DexPreflight.fail("proto.shorty_idx 越界 i=" + i);
            if (returnTypeIdx >= typeIdsSize) return DexPreflight.fail("proto.return_type_idx 越界 i=" + i);
            if (parametersOff != 0 && parametersOff + 4 > fileSize) return DexPreflight.fail("proto.parameters_off 越界 i=" + i);
        }
        for (long i = 0; i < fieldIdsSize; i++) {
            long base = fieldIdsOff + i * 8L;
            int classIdx = readU16At(raf, base);
            int typeIdx = readU16At(raf, base + 2);
            long nameIdx = readU32At(raf, base + 4);
            if (classIdx >= typeIdsSize || typeIdx >= typeIdsSize || nameIdx >= stringIdsSize) return DexPreflight.fail("field_id 索引越界 i=" + i);
        }
        for (long i = 0; i < methodIdsSize; i++) {
            long base = methodIdsOff + i * 8L;
            int classIdx = readU16At(raf, base);
            int protoIdx = readU16At(raf, base + 2);
            long nameIdx = readU32At(raf, base + 4);
            if (classIdx >= typeIdsSize || protoIdx >= protoIdsSize || nameIdx >= stringIdsSize) return DexPreflight.fail("method_id 索引越界 i=" + i);
        }
        for (long i = 0; i < classDefsSize; i++) {
            long base = classDefsOff + i * 32L;
            long classIdx = readU32At(raf, base);
            long superclassIdx = readU32At(raf, base + 8);
            long interfacesOff = readU32At(raf, base + 12);
            long sourceFileIdx = readU32At(raf, base + 16);
            long annotationsOff = readU32At(raf, base + 20);
            long classDataOff = readU32At(raf, base + 24);
            long staticValuesOff = readU32At(raf, base + 28);
            if (classIdx >= typeIdsSize) return DexPreflight.fail("class_def.class_idx 越界 i=" + i);
            if (superclassIdx != 0xffffffffL && superclassIdx >= typeIdsSize) return DexPreflight.fail("class_def.superclass_idx 越界 i=" + i);
            if (sourceFileIdx != 0xffffffffL && sourceFileIdx >= stringIdsSize) return DexPreflight.fail("class_def.source_file_idx 越界 i=" + i);
            if (interfacesOff != 0 && interfacesOff + 4 > fileSize) return DexPreflight.fail("class_def.interfaces_off 越界 i=" + i);
            if (annotationsOff != 0 && annotationsOff >= fileSize) return DexPreflight.fail("class_def.annotations_off 越界 i=" + i);
            if (classDataOff != 0 && classDataOff >= fileSize) return DexPreflight.fail("class_def.class_data_off 越界 i=" + i);
            if (staticValuesOff != 0 && staticValuesOff >= fileSize) return DexPreflight.fail("class_def.static_values_off 越界 i=" + i);
        }
        return DexPreflight.ok();
    }

    private static DexPreflight validateStringData(RandomAccessFile raf, long fileSize, long stringIdsSize, long stringIdsOff) throws Exception {
        final long maxStringsToScan = Math.min(stringIdsSize, 300_000L);
        for (long i = 0; i < maxStringsToScan; i++) {
            long off = readU32At(raf, stringIdsOff + i * 4L);
            if (off <= 0 || off >= fileSize) return DexPreflight.fail("string_data_off 越界 i=" + i + " off=" + off);
            DexPreflight ok = validateOneStringData(raf, fileSize, off, i);
            if (!ok.ok) return ok;
        }
        return DexPreflight.ok();
    }

    private static DexPreflight validateOneStringData(RandomAccessFile raf, long fileSize, long off, long index) throws Exception {
        raf.seek(off);
        long cursor = off;
        int shift = 0;
        int b;
        do {
            if (cursor >= fileSize) return DexPreflight.fail("string_data uleb128 越界 i=" + index + " off=" + off);
            b = raf.readUnsignedByte();
            cursor++;
            shift += 7;
            if (shift > 35) return DexPreflight.fail("string_data uleb128 过长 i=" + index + " off=" + off);
        } while ((b & 0x80) != 0);
        long scanned = 0;
        while (cursor < fileSize && scanned < 1024L * 1024L) {
            int c = raf.readUnsignedByte();
            cursor++;
            if (c == 0) return DexPreflight.ok();
            scanned++;
        }
        return DexPreflight.fail("string_data 未在文件内结束 i=" + index + " off=" + off);
    }

    private static boolean validTable(long fileSize, long size, long off, long itemSize) {
        if (size == 0) return true;
        if (off <= 0 || off >= fileSize) return false;
        return off + size * itemSize <= fileSize;
    }

    private static long u32(byte[] buf, int off) {
        return ((long) buf[off] & 0xffL)
                | (((long) buf[off + 1] & 0xffL) << 8)
                | (((long) buf[off + 2] & 0xffL) << 16)
                | (((long) buf[off + 3] & 0xffL) << 24);
    }

    private static long readU32(RandomAccessFile raf) throws Exception {
        return ((long) raf.readUnsignedByte())
                | (((long) raf.readUnsignedByte()) << 8)
                | (((long) raf.readUnsignedByte()) << 16)
                | (((long) raf.readUnsignedByte()) << 24);
    }

    private static int readU16(RandomAccessFile raf) throws Exception {
        return raf.readUnsignedByte() | (raf.readUnsignedByte() << 8);
    }

    private static long readU32At(RandomAccessFile raf, long off) throws Exception {
        raf.seek(off);
        return readU32(raf);
    }

    private static int readU16At(RandomAccessFile raf, long off) throws Exception {
        raf.seek(off);
        return readU16(raf);
    }

    private static List<String> dexEntryNames(File file, MultiDexContainer<? extends DexBackedDexFile> container) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            names.addAll(container.getDexEntryNames());
        } catch (Throwable ignored) {
        }
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apk") || lower.endsWith(".jar") || lower.endsWith(".zip")) {
            try (ZipFile zip = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry == null || entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (name != null && name.endsWith(".dex")) names.add(name);
                }
            } catch (Throwable ignored) {
            }
        }
        return new ArrayList<>(names);
    }

    private static String sourceType(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".dex")) return "dex";
        if (name.endsWith(".apk")) return "apk";
        if (name.endsWith(".jar")) return "jar";
        if (name.endsWith(".zip")) return "zip";
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int a = in.read();
            int b = in.read();
            int c = in.read();
            int d = in.read();
            if (a == 'd' && b == 'e' && c == 'x' && d == '\n') return "dex";
            if (a == 'P' && b == 'K') return "zip";
        } catch (Throwable ignored) {
        }
        return "file";
    }

    static final class CookieDumpOptions {
        String outputDir;
        int maxDexBytes = 256 * 1024 * 1024;
        int maxDumpCount = 128;
        boolean includeParents = true;
        boolean includeThreadContext = true;
        boolean registerSources = true;
        boolean verbose = false;
        ClassLoader loader = null;

        static CookieDumpOptions from(Object raw, String packageName) {
            CookieDumpOptions options = new CookieDumpOptions();
            options.outputDir = defaultCookieDumpDir(packageName);
            Object obj = unwrap(raw);
            if (obj instanceof Scriptable) {
                Scriptable s = (Scriptable) obj;
                String outputDir = asString(get(s, "outputDir"));
                if (outputDir == null) outputDir = asString(get(s, "dir"));
                if (outputDir == null) outputDir = asString(get(s, "path"));
                if (outputDir != null) options.outputDir = outputDir;
                options.maxDexBytes = asInt(get(s, "maxDexBytes"), options.maxDexBytes);
                options.maxDexBytes = asInt(get(s, "maxDumpBytes"), options.maxDexBytes);
                options.maxDumpCount = asInt(get(s, "maxDumpCount"), options.maxDumpCount);
                options.includeParents = asBoolean(get(s, "includeParents"), options.includeParents);
                options.includeThreadContext = asBoolean(get(s, "includeThreadContext"), options.includeThreadContext);
                options.registerSources = asBoolean(get(s, "registerSources"), options.registerSources);
                options.registerSources = asBoolean(get(s, "register"), options.registerSources);
                options.verbose = asBoolean(get(s, "verbose"), options.verbose);
                options.loader = asClassLoader(get(s, "loader"), options.loader);
                if (options.loader == null) options.loader = asClassLoader(get(s, "classLoader"), null);
                if (options.loader == null) options.loader = asClassLoader(get(s, "appClassLoader"), null);
            }
            options.outputDir = normalizeCookieOutputDir(options.outputDir, packageName);
            if (options.maxDexBytes < 0x70) options.maxDexBytes = 0x70;
            if (options.maxDexBytes > 512 * 1024 * 1024) options.maxDexBytes = 512 * 1024 * 1024;
            if (options.maxDumpCount <= 0) options.maxDumpCount = 128;
            if (options.maxDumpCount > 1024) options.maxDumpCount = 1024;
            return options;
        }
    }


    static final class DumpDecryptedTargetOptions {
        String className = null;
        String methodName = null;
        String proto = null;
        String cookieDir;
        String cookiePrefix = "cookie_";
        String outputDir;
        String exportName = null;
        int maxDexBytes = 512 * 1024 * 1024;
        int maxDumpCount = 256;
        int maxFiles = 300;
        int maxSmaliChars = 120_000;
        boolean includeSmali = false;
        boolean includeParents = false;
        boolean includeThreadContext = false;
        boolean clearCookieDir = true;
        boolean verbose = false;
        ClassLoader loader = null;

        static DumpDecryptedTargetOptions from(Object raw, String packageName) {
            DumpDecryptedTargetOptions options = new DumpDecryptedTargetOptions();
            String pkg = packageName == null || packageName.trim().isEmpty() ? "unknown" : packageName.trim();
            options.cookieDir = defaultCookieDumpDir(pkg);
            options.outputDir = defaultCookieDumpDir(pkg);
            Object obj = unwrap(raw);
            if (obj instanceof Scriptable) {
                Scriptable s = (Scriptable) obj;
                String v;
                v = asString(get(s, "className")); if (v == null) v = asString(get(s, "class")); if (v != null) options.className = v;
                v = asString(get(s, "methodName")); if (v == null) v = asString(get(s, "name")); if (v != null) options.methodName = v;
                v = asString(get(s, "proto")); if (v == null) v = asString(get(s, "descriptor")); if (v != null) options.proto = v;
                v = asString(get(s, "cookieDir")); if (v == null) v = asString(get(s, "dir")); if (v != null) options.cookieDir = v;
                v = asString(get(s, "cookiePrefix")); if (v == null) v = asString(get(s, "prefix")); if (v != null) options.cookiePrefix = v;
                v = asString(get(s, "outputDir")); if (v == null) v = asString(get(s, "exportDir")); if (v != null) options.outputDir = v;
                v = asString(get(s, "exportName")); if (v == null) v = asString(get(s, "fileName")); if (v != null) options.exportName = v;
                options.maxDexBytes = asInt(get(s, "maxDexBytes"), options.maxDexBytes);
                options.maxDexBytes = asInt(get(s, "maxDumpBytes"), options.maxDexBytes);
                options.maxDumpCount = asInt(get(s, "maxDumpCount"), options.maxDumpCount);
                options.maxFiles = asInt(get(s, "maxFiles"), options.maxFiles);
                options.maxSmaliChars = asInt(get(s, "maxSmaliChars"), options.maxSmaliChars);
                options.includeSmali = asBoolean(get(s, "includeSmali"), options.includeSmali);
                options.includeParents = asBoolean(get(s, "includeParents"), options.includeParents);
                options.includeThreadContext = asBoolean(get(s, "includeThreadContext"), options.includeThreadContext);
                options.clearCookieDir = asBoolean(get(s, "clearCookieDir"), options.clearCookieDir);
                options.clearCookieDir = asBoolean(get(s, "clear"), options.clearCookieDir);
                options.verbose = asBoolean(get(s, "verbose"), options.verbose);
                options.loader = asClassLoader(get(s, "loader"), options.loader);
                if (options.loader == null) options.loader = asClassLoader(get(s, "classLoader"), null);
                if (options.loader == null) options.loader = asClassLoader(get(s, "appClassLoader"), null);
            }
            if (options.className == null || options.className.trim().isEmpty()) {
                throw new IllegalArgumentException("className 不能为空；dumpDecryptedDexForMethod 需要指定目标类");
            }
            if (options.methodName == null || options.methodName.trim().isEmpty()) {
                throw new IllegalArgumentException("methodName 不能为空；dumpDecryptedDexForMethod 需要指定目标方法");
            }
            if (options.proto != null && options.proto.trim().isEmpty()) options.proto = null;
            if (options.cookiePrefix == null) options.cookiePrefix = "cookie_";
            if (options.cookieDir == null || options.cookieDir.trim().isEmpty()) options.cookieDir = defaultCookieDumpDir(pkg);
            if (options.outputDir == null || options.outputDir.trim().isEmpty()) options.outputDir = defaultCookieDumpDir(pkg);
            if (options.exportName == null || options.exportName.trim().isEmpty()) {
                options.exportName = options.className.replace('.', '_').replace('/', '_') + "_" + options.methodName + "_unpacked.dex";
            }
            if (options.maxDexBytes < 0x70) options.maxDexBytes = 0x70;
            if (options.maxDexBytes > 512 * 1024 * 1024) options.maxDexBytes = 512 * 1024 * 1024;
            if (options.maxDumpCount <= 0) options.maxDumpCount = 256;
            if (options.maxDumpCount > 1024) options.maxDumpCount = 1024;
            if (options.maxFiles <= 0) options.maxFiles = 300;
            if (options.maxFiles > 2048) options.maxFiles = 2048;
            if (options.maxSmaliChars < 0) options.maxSmaliChars = 0;
            if (options.maxSmaliChars > DEFAULT_MAX_SMALI_CHARS) options.maxSmaliChars = DEFAULT_MAX_SMALI_CHARS;
            return options;
        }
    }

    static final class MemoryScanOptions {
        String outputDir;
        int maxRegionBytes = 256 * 1024 * 1024;
        int maxDumpBytes = 256 * 1024 * 1024;
        int maxDumpCount = 32;
        boolean includeAnonymous = true;
        boolean includeFileBacked = false;
        boolean relaxed = false;
        boolean includeInvalidRaw = true;
        int rawWindowBytes = 128 * 1024 * 1024;
        Boolean registerSources = null;
        boolean requireAllAsciiContains = false;
        List<String> requireAsciiContains = new ArrayList<>();

        static MemoryScanOptions from(Object raw, String packageName) {
            MemoryScanOptions options = new MemoryScanOptions();
            String pkg = packageName == null || packageName.trim().isEmpty() ? "unknown" : packageName.trim();
            options.outputDir = "/data/user/0/" + pkg + "/code_cache/xhh_memory_dex";
            Object obj = unwrap(raw);
            if (obj instanceof Scriptable) {
                Scriptable s = (Scriptable) obj;
                String outputDir = asString(get(s, "outputDir"));
                if (outputDir != null) options.outputDir = outputDir;
                options.maxRegionBytes = asInt(get(s, "maxRegionBytes"), options.maxRegionBytes);
                options.maxDumpBytes = asInt(get(s, "maxDumpBytes"), options.maxDumpBytes);
                options.maxDumpCount = asInt(get(s, "maxDumpCount"), options.maxDumpCount);
                options.includeAnonymous = asBoolean(get(s, "includeAnonymous"), options.includeAnonymous);
                options.includeFileBacked = asBoolean(get(s, "includeFileBacked"), options.includeFileBacked);
                options.relaxed = asBoolean(get(s, "relaxed"), options.relaxed);
                options.includeInvalidRaw = asBoolean(get(s, "includeInvalidRaw"), options.includeInvalidRaw);
                options.includeInvalidRaw = asBoolean(get(s, "includeInvalid"), options.includeInvalidRaw);
                options.rawWindowBytes = asInt(get(s, "rawWindowBytes"), options.rawWindowBytes);
                Object strict = get(s, "strict");
                if (strict != null) options.relaxed = !asBoolean(strict, !options.relaxed);
                Object register = get(s, "registerSources");
                if (register == null) register = get(s, "register");
                if (register != null) options.registerSources = asBoolean(register, true);
                options.requireAllAsciiContains = asBoolean(get(s, "requireAllAsciiContains"), options.requireAllAsciiContains);
                options.requireAllAsciiContains = asBoolean(get(s, "matchAllContains"), options.requireAllAsciiContains);
                options.requireAsciiContains = asStringList(get(s, "requireAsciiContains"));
                if (options.requireAsciiContains.isEmpty()) {
                    options.requireAsciiContains = asStringList(get(s, "contains"));
                }
            }
            if (options.maxRegionBytes < 1024 * 1024) options.maxRegionBytes = 1024 * 1024;
            if (options.maxRegionBytes > 512 * 1024 * 1024) options.maxRegionBytes = 512 * 1024 * 1024;
            if (options.maxDumpBytes < 1024 * 1024) options.maxDumpBytes = 1024 * 1024;
            if (options.maxDumpBytes > 512 * 1024 * 1024) options.maxDumpBytes = 512 * 1024 * 1024;
            if (options.maxDumpCount <= 0) options.maxDumpCount = 32;
            if (options.maxDumpCount > 256) options.maxDumpCount = 256;
            if (options.rawWindowBytes < 1024 * 1024) options.rawWindowBytes = 1024 * 1024;
            if (options.rawWindowBytes > 256 * 1024 * 1024) options.rawWindowBytes = 256 * 1024 * 1024;
            return options;
        }
    }

    static final class StringScanOptions {
        String outputDir;
        int maxRegionBytes = 256 * 1024 * 1024;
        int maxHits = 64;
        int contextBytes = 96;
        int windowBytes = 1024 * 1024;
        boolean includeAnonymous = true;
        boolean includeFileBacked = true;
        boolean requireAll = false;
        boolean dumpWindows = false;
        List<String> needles = new ArrayList<>();

        static StringScanOptions from(Object raw, String packageName) {
            StringScanOptions options = new StringScanOptions();
            options.outputDir = defaultDumpDir(packageName) + "_string_windows";
            Object obj = unwrap(raw);
            if (obj instanceof String) {
                String s = asString(obj);
                if (s != null) options.needles.add(s);
            } else if (obj instanceof Scriptable) {
                Scriptable scriptable = (Scriptable) obj;
                String outputDir = asString(get(scriptable, "outputDir"));
                if (outputDir == null) outputDir = asString(get(scriptable, "dir"));
                if (outputDir == null) outputDir = asString(get(scriptable, "path"));
                if (outputDir != null) options.outputDir = outputDir;
                options.maxRegionBytes = asInt(get(scriptable, "maxRegionBytes"), options.maxRegionBytes);
                options.maxHits = asInt(get(scriptable, "maxHits"), options.maxHits);
                options.contextBytes = asInt(get(scriptable, "contextBytes"), options.contextBytes);
                options.windowBytes = asInt(get(scriptable, "windowBytes"), options.windowBytes);
                options.includeAnonymous = asBoolean(get(scriptable, "includeAnonymous"), options.includeAnonymous);
                options.includeFileBacked = asBoolean(get(scriptable, "includeFileBacked"), options.includeFileBacked);
                options.requireAll = asBoolean(get(scriptable, "requireAll"), options.requireAll);
                options.requireAll = asBoolean(get(scriptable, "matchAll"), options.requireAll);
                options.dumpWindows = asBoolean(get(scriptable, "dumpWindows"), options.dumpWindows);
                options.needles = asStringList(get(scriptable, "needles"));
                if (options.needles.isEmpty()) options.needles = asStringList(get(scriptable, "strings"));
                if (options.needles.isEmpty()) options.needles = asStringList(get(scriptable, "contains"));
            }
            ArrayList<String> clean = new ArrayList<>();
            for (String n : options.needles) {
                if (n != null && !n.trim().isEmpty() && !clean.contains(n)) clean.add(n);
            }
            options.needles = clean;
            if (options.outputDir == null || options.outputDir.trim().isEmpty()) options.outputDir = defaultDumpDir(packageName) + "_string_windows";
            if (options.maxRegionBytes < 1024 * 1024) options.maxRegionBytes = 1024 * 1024;
            if (options.maxRegionBytes > 512 * 1024 * 1024) options.maxRegionBytes = 512 * 1024 * 1024;
            if (options.maxHits <= 0) options.maxHits = 64;
            if (options.maxHits > 4096) options.maxHits = 4096;
            if (options.contextBytes < 0) options.contextBytes = 0;
            if (options.contextBytes > 4096) options.contextBytes = 4096;
            if (options.windowBytes < 0) options.windowBytes = 0;
            if (options.windowBytes > 16 * 1024 * 1024) options.windowBytes = 16 * 1024 * 1024;
            return options;
        }
    }

    static final class DumpDirOptions {
        String dir;
        String repairedDir;
        String prefix = "memdex_";
        long minBytes = 0x70;
        long maxBytes = DEFAULT_MAX_DEX_BYTES;
        int limit = 0;
        boolean repair = false;
        boolean dedupe = true;
        boolean matchAllContains = false;
        boolean trimToHeaderSize = true;
        boolean padToHeaderSize = true;
        boolean updateFileSize = true;
        boolean recomputeSignature = true;
        boolean recomputeChecksum = true;
        int tailPaddingBytes = 0;
        int rawScanBytes = 64 * 1024 * 1024;
        boolean openOnlyRawMatched = false;
        boolean verbose = false;
        int progressEvery = 10;
        List<String> contains = new ArrayList<>();

        static DumpDirOptions from(Object raw, String packageName) {
            DumpDirOptions options = new DumpDirOptions();
            options.dir = defaultDumpDir(packageName);
            options.repairedDir = options.dir + "_repaired";
            Object obj = unwrap(raw);
            if (obj instanceof String) {
                String path = asString(obj);
                if (path != null) {
                    options.dir = path;
                    options.repairedDir = path + "_repaired";
                }
            } else if (obj instanceof Scriptable) {
                Scriptable s = (Scriptable) obj;
                String dir = asString(get(s, "dir"));
                if (dir == null) dir = asString(get(s, "path"));
                if (dir == null) dir = asString(get(s, "outputDir"));
                if (dir != null) {
                    options.dir = dir;
                    options.repairedDir = dir + "_repaired";
                }
                String repairedDir = asString(get(s, "repairedDir"));
                if (repairedDir != null) options.repairedDir = repairedDir;
                String prefix = asString(get(s, "prefix"));
                if (prefix != null) options.prefix = prefix;
                Object maxBytes = get(s, "maxDexBytes");
                if (maxBytes == null) maxBytes = get(s, "maxBytes");
                options.maxBytes = asLong(maxBytes, options.maxBytes);
                options.minBytes = asLong(get(s, "minBytes"), options.minBytes);
                options.limit = asInt(get(s, "limit"), options.limit);
                options.repair = asBoolean(get(s, "repair"), options.repair);
                options.dedupe = asBoolean(get(s, "dedupe"), options.dedupe);
                options.matchAllContains = asBoolean(get(s, "matchAllContains"), options.matchAllContains);
                options.matchAllContains = asBoolean(get(s, "requireAllContains"), options.matchAllContains);
                options.trimToHeaderSize = asBoolean(get(s, "trimToHeaderSize"), options.trimToHeaderSize);
                options.padToHeaderSize = asBoolean(get(s, "padToHeaderSize"), options.padToHeaderSize);
                options.updateFileSize = asBoolean(get(s, "updateFileSize"), options.updateFileSize);
                options.recomputeSignature = asBoolean(get(s, "recomputeSignature"), options.recomputeSignature);
                options.recomputeChecksum = asBoolean(get(s, "recomputeChecksum"), options.recomputeChecksum);
                options.tailPaddingBytes = asInt(get(s, "tailPaddingBytes"), options.tailPaddingBytes);
                options.rawScanBytes = asInt(get(s, "rawScanBytes"), options.rawScanBytes);
                options.openOnlyRawMatched = asBoolean(get(s, "openOnlyRawMatched"), options.openOnlyRawMatched);
                options.openOnlyRawMatched = asBoolean(get(s, "onlyRawMatched"), options.openOnlyRawMatched);
                options.verbose = asBoolean(get(s, "verbose"), options.verbose);
                options.progressEvery = asInt(get(s, "progressEvery"), options.progressEvery);
                options.contains = asStringList(get(s, "contains"));
                if (options.contains.isEmpty()) options.contains = asStringList(get(s, "strings"));
                if (options.contains.isEmpty()) options.contains = asStringList(get(s, "requireAsciiContains"));
            }
            if (options.maxBytes < 0x70) options.maxBytes = 0x70;
            if (options.maxBytes > HARD_MAX_DEX_BYTES) options.maxBytes = HARD_MAX_DEX_BYTES;
            if (options.minBytes < 0) options.minBytes = 0;
            if (options.limit < 0) options.limit = 0;
            if (options.tailPaddingBytes < 0) options.tailPaddingBytes = 0;
            if (options.tailPaddingBytes > 1024 * 1024) options.tailPaddingBytes = 1024 * 1024;
            if (options.rawScanBytes <= 0) options.rawScanBytes = 64 * 1024 * 1024;
            if (options.progressEvery <= 0) options.progressEvery = 10;
            return options;
        }
    }

    public static final class DexFileView {
        private final List<DexUnit> units;
        private final ClassLoader defaultLoader;
        private final ArrayList<Map<String, Object>> skippedSources;

        DexFileView(List<DexUnit> units, ClassLoader defaultLoader, List<Map<String, Object>> skippedSources) {
            this.units = units;
            this.defaultLoader = defaultLoader;
            this.skippedSources = new ArrayList<>();
            if (skippedSources != null) this.skippedSources.addAll(skippedSources);
        }

        public Object skippedSources() {
            return js(skippedSourcesRaw());
        }

        List<Map<String, Object>> skippedSourcesRaw() {
            return new ArrayList<>(skippedSources);
        }

        public Object errors() {
            return skippedSources();
        }

        private void recordSkip(DexSource source, String stage, Throwable t) {
            Map<String, Object> map = skipMap(source, stage, t);
            skippedSources.add(map);
            Log.w(TAG, "skip dex while " + stage + ": " + (source == null ? "null" : source.path), t);
        }

        public Object sources() {
            return js(sourcesRaw());
        }

        List<Map<String, Object>> sourcesRaw() {
            ArrayList<Map<String, Object>> out = new ArrayList<>();
            for (DexUnit unit : units) out.add(unit.source.toMap());
            return out;
        }

        public Object classes() {
            return js(classesRaw());
        }

        List<DexClassView> classesRaw() {
            ArrayList<DexClassView> out = new ArrayList<>();
            int count = 0;
            for (DexUnit unit : units) {
                try {
                    Iterable<? extends ClassDef> iterable = unit.dexFile.getClasses();
                    if (iterable == null) continue;
                    Iterator<? extends ClassDef> iterator = iterable.iterator();
                    while (true) {
                        ClassDef classDef;
                        try {
                            if (iterator == null || !iterator.hasNext()) break;
                            classDef = iterator.next();
                            if (classDef == null) continue;
                            out.add(new DexClassView(unit, classDef, unit.source.loader == null ? defaultLoader : unit.source.loader));
                        } catch (Throwable itemError) {
                            recordSkip(unit.source, "classes-item", itemError);
                            break;
                        }
                        if (++count > maxClasses) return out;
                    }
                } catch (Throwable t) {
                    recordSkip(unit.source, "classes", t);
                }
            }
            return out;
        }

        public DexClassView findClass(String descriptor) {
            if (descriptor == null) return null;
            String clean = descriptor.trim();
            for (DexClassView view : classesRaw()) {
                if (clean.equals(view.descriptor)) return view;
            }
            return null;
        }

        public DexClassView findClassByName(String className) {
            if (className == null) return null;
            String descriptor = DexProtoUtils.toDescriptor(className);
            return findClass(descriptor);
        }

        public Object strings() {
            return js(stringsRaw());
        }

        List<String> stringsRaw() {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (DexClassView cls : classesRaw()) out.addAll(cls.stringsRaw());
            return new ArrayList<>(out);
        }

        public Object findMethods(Object queryObject) {
            return js(findMethodsRaw(queryObject).toListRaw());
        }

        DexSearchResult findMethodsRaw(Object queryObject) {
            Query query = queryObject instanceof Query ? (Query) queryObject : Query.from(queryObject);
            if (!query.hasAnyCondition()) {
                throw new IllegalArgumentException("findMethods 至少需要一个搜索条件；例如 strings、containsString、smaliContains、proto、className、methodName 或 invokeContains");
            }
            ArrayList<DexMethodHit> hits = new ArrayList<>();
            int visitedMethods = 0;
            int visitedClasses = 0;
            List<DexClassView> classViews = classesRaw();
            if (query.verbose) {
                Log.i(TAG, "findMethods start classes=" + classViews.size() + " sources=" + units.size() + " query={" + query.summary() + "}");
            }
            outer:
            for (DexClassView cls : classViews) {
                visitedClasses++;
                try {
                    if (query.classPrefix != null && !cls.name.startsWith(query.classPrefix)) continue;
                    if (query.className != null && !cls.name.equals(query.className) && !cls.descriptor.equals(query.className)) continue;
                    if (query.excludedClass(cls.name)) {
                        if (query.verbose && (visitedClasses == 1 || visitedClasses % query.progressEveryClasses == 0)) {
                            Log.i(TAG, "findMethods skip excluded class " + visitedClasses + "/" + classViews.size() + " " + cls.name);
                        }
                        continue;
                    }
                    List<DexMethodView> methods = cls.methodsRaw();
                    if (query.verbose && (visitedClasses == 1 || visitedClasses % query.progressEveryClasses == 0 || visitedClasses == classViews.size())) {
                        Log.i(TAG, "findMethods scan class " + visitedClasses + "/" + classViews.size()
                                + " methods=" + methods.size() + " class=" + cls.name
                                + " source=" + (cls.unit == null || cls.unit.source == null ? null : cls.unit.source.path));
                    }
                    for (DexMethodView method : methods) {
                        visitedMethods++;
                        if (visitedMethods > maxMethods) break outer;
                        if (query.verbose && query.progressEveryMethods > 0 && visitedMethods % query.progressEveryMethods == 0) {
                            Log.i(TAG, "findMethods progress methods=" + visitedMethods + " class=" + cls.name + " method=" + method.name + method.descriptor);
                        }
                        try {
                            DexMethodHit hit = method.match(query);
                            if (hit != null && hit.score >= query.minScore) {
                                hits.add(hit);
                                if (query.verbose) {
                                    Log.i(TAG, "findMethods hit score=" + hit.score + " reasons=" + hit.reasonList
                                            + " class=" + hit.className + " method=" + hit.methodName + hit.descriptor
                                            + " source=" + hit.sourcePath);
                                }
                                if (query.limit > 0 && hits.size() >= query.limit) break outer;
                            }
                        } catch (Throwable methodError) {
                            recordSkip(method.unit.source, "method-match", methodError);
                        }
                    }
                } catch (Throwable classError) {
                    recordSkip(cls.unit.source, "class-match", classError);
                }
            }
            hits.sort(new Comparator<DexMethodHit>() {
                @Override public int compare(DexMethodHit a, DexMethodHit b) { return Integer.compare(b.score, a.score); }
            });
            if (query.verbose) Log.i(TAG, "findMethods done classes=" + visitedClasses + " methods=" + visitedMethods + " hits=" + hits.size());
            return new DexSearchResult(hits);
        }

        public Object findMethod(Object queryObject) {
            DexMethodHit hit = findMethodsRaw(queryObject).best();
            return hit == null ? null : js(hit.toMapRaw());
        }
    }

    public static final class DexClassView {
        private final DexUnit unit;
        private final ClassDef classDef;
        private final ClassLoader defaultLoader;
        public final String name;
        public final String descriptor;
        public final int accessFlags;

        DexClassView(DexUnit unit, ClassDef classDef, ClassLoader defaultLoader) {
            this.unit = unit;
            this.classDef = classDef;
            this.defaultLoader = defaultLoader;
            String desc;
            int flags;
            try { desc = classDef.getType(); } catch (Throwable t) { desc = "L<invalid>;"; }
            try { flags = classDef.getAccessFlags(); } catch (Throwable t) { flags = 0; }
            this.descriptor = desc;
            this.name = DexProtoUtils.toJavaName(descriptor);
            this.accessFlags = flags;
        }

        public Object methods() {
            return js(methodsRaw());
        }

        List<DexMethodView> methodsRaw() {
            ArrayList<DexMethodView> out = new ArrayList<>();
            try {
                Iterable<? extends Method> iterable = classDef.getMethods();
                if (iterable == null) return out;
                Iterator<? extends Method> iterator = iterable.iterator();
                while (true) {
                    try {
                        if (iterator == null || !iterator.hasNext()) break;
                        Method method = iterator.next();
                        if (method != null) out.add(new DexMethodView(unit, classDef, method, defaultLoader));
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            return out;
        }

        public Object fields() {
            ArrayList<Map<String, Object>> out = new ArrayList<>();
            try {
                Iterable<? extends Field> iterable = classDef.getFields();
                if (iterable == null) return js(out);
                Iterator<? extends Field> iterator = iterable.iterator();
                while (true) {
                    Field field;
                    try {
                        if (iterator == null || !iterator.hasNext()) break;
                        field = iterator.next();
                        if (field == null) continue;
                        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                        m.put("name", field.getName());
                        m.put("type", field.getType());
                        m.put("accessFlags", field.getAccessFlags());
                        out.add(m);
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            return js(out);
        }

        public Object strings() {
            return js(stringsRaw());
        }

        List<String> stringsRaw() {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (DexMethodView method : methodsRaw()) out.addAll(method.stringsRaw());
            return new ArrayList<>(out);
        }

        public DexMethodView findMethod(String name) { return findMethod(name, null); }

        public DexMethodView findMethod(String name, String proto) {
            for (DexMethodView m : methodsRaw()) {
                if (name != null && !name.equals(m.name)) continue;
                if (proto != null && !proto.equals(m.descriptor)) continue;
                return m;
            }
            return null;
        }

        public String smali() {
            StringBuilder sb = new StringBuilder();
            sb.append(".class ").append(DexSmaliPrinter.accessFlags(accessFlags)).append(' ').append(descriptor).append('\n');
            if (classDef.getSuperclass() != null) sb.append(".super ").append(classDef.getSuperclass()).append('\n');
            for (String iface : classDef.getInterfaces()) sb.append(".implements ").append(iface).append('\n');
            sb.append('\n');
            for (DexMethodView method : methodsRaw()) {
                sb.append(method.smali()).append('\n');
                if (sb.length() > maxSmaliChars) {
                    sb.append("# ... truncated ...\n");
                    break;
                }
            }
            return sb.toString();
        }
    }

    public static final class DexMethodView {
        private final DexUnit unit;
        private final ClassDef classDef;
        private final Method method;
        private final ClassLoader defaultLoader;
        public final String className;
        public final String classDescriptor;
        public final String name;
        public final String descriptor;
        public final String returnType;
        private final List<String> parameterList;
        public final Object parameters;
        public final int accessFlags;

        DexMethodView(DexUnit unit, ClassDef classDef, Method method, ClassLoader defaultLoader) {
            this.unit = unit;
            this.classDef = classDef;
            this.method = method;
            this.defaultLoader = defaultLoader;
            String clsDesc;
            String methodName;
            String ret;
            int flags;
            try { clsDesc = classDef.getType(); } catch (Throwable t) { clsDesc = "L<invalid>;"; }
            try { methodName = method.getName(); } catch (Throwable t) { methodName = "<invalid>"; }
            try { ret = method.getReturnType(); } catch (Throwable t) { ret = "V"; }
            this.classDescriptor = clsDesc;
            this.className = DexProtoUtils.toJavaName(classDescriptor);
            this.name = methodName;
            this.returnType = ret;
            this.parameterList = new ArrayList<>();
            try {
                for (CharSequence p : method.getParameterTypes()) this.parameterList.add(String.valueOf(p));
            } catch (Throwable ignored) {
            }
            this.parameters = js(this.parameterList);
            String proto;
            try { proto = DexProtoUtils.proto(method); } catch (Throwable t) { proto = "(" + this.parameterList.size() + ")" + this.returnType; }
            this.descriptor = proto;
            try { flags = method.getAccessFlags(); } catch (Throwable t) { flags = 0; }
            this.accessFlags = flags;
        }

        public boolean isStatic() { return AccessFlags.STATIC.isSet(accessFlags); }
        public boolean isConstructor() { return "<init>".equals(name); }
        public ClassLoader loader() { return defaultLoader; }
        List<String> parametersRaw() { return new ArrayList<>(parameterList); }

        public Object strings() {
            return js(stringsRaw());
        }

        List<String> stringsRaw() {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            try {
                MethodImplementation impl = method.getImplementation();
                if (impl == null) return new ArrayList<>(out);
                Iterator<? extends Instruction> iterator = impl.getInstructions().iterator();
                while (true) {
                    Instruction ins;
                    try {
                        if (iterator == null || !iterator.hasNext()) break;
                        ins = iterator.next();
                        if (ins instanceof ReferenceInstruction) {
                            Reference ref = ((ReferenceInstruction) ins).getReference();
                            if (ref instanceof StringReference) out.add(((StringReference) ref).getString());
                        }
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            return new ArrayList<>(out);
        }

        public Object invokes() {
            return js(invokesRaw());
        }

        List<String> invokesRaw() {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            try {
                MethodImplementation impl = method.getImplementation();
                if (impl == null) return new ArrayList<>(out);
                Iterator<? extends Instruction> iterator = impl.getInstructions().iterator();
                while (true) {
                    Instruction ins;
                    try {
                        if (iterator == null || !iterator.hasNext()) break;
                        ins = iterator.next();
                        String opcode = ins.getOpcode().name;
                        if (!opcode.startsWith("invoke-")) continue;
                        if (ins instanceof ReferenceInstruction) out.add(referenceString(((ReferenceInstruction) ins).getReference()));
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            return new ArrayList<>(out);
        }

        public Object instructions() {
            ArrayList<Map<String, Object>> out = new ArrayList<>();
            try {
                MethodImplementation impl = method.getImplementation();
                if (impl == null) return js(out);
                int offset = 0;
                Iterator<? extends Instruction> iterator = impl.getInstructions().iterator();
                while (true) {
                    Instruction ins;
                    try {
                        if (iterator == null || !iterator.hasNext()) break;
                        ins = iterator.next();
                        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                        String opcode = ins.getOpcode().name;
                        String ref = null;
                        if (ins instanceof ReferenceInstruction) ref = referenceString(((ReferenceInstruction) ins).getReference());
                        m.put("offset", offset);
                        m.put("opcode", opcode);
                        m.put("reference", ref);
                        m.put("text", ref == null ? opcode : opcode + " " + ref);
                        out.add(m);
                        offset += ins.getCodeUnits();
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            return js(out);
        }

        public String smali() {
            try {
                return DexSmaliPrinter.method(method, maxSmaliChars);
            } catch (Throwable t) {
                return "# smali failed: " + brief(t) + "\n";
            }
        }

        public Executable toMethod(Object loaderObject) throws Exception {
            ClassLoader loader = loaderObject instanceof ClassLoader ? (ClassLoader) loaderObject : defaultLoader;
            Class<?> clazz = Class.forName(className, false, loader);
            Class<?>[] params = DexProtoUtils.toClassArray(parameterList, loader);
            if ("<clinit>".equals(name)) throw new UnsupportedOperationException("<clinit> 不能转换为 Method/Constructor");
            if ("<init>".equals(name)) {
                Constructor<?> c = clazz.getDeclaredConstructor(params);
                c.setAccessible(true);
                return c;
            }
            java.lang.reflect.Method m = clazz.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        }

        DexMethodHit match(Query query) {
            int score = 0;
            ArrayList<String> reasons = new ArrayList<>();
            if (query.methodName != null) {
                if (!query.methodName.equals(name)) return null;
                score += 20; reasons.add("name");
            }
            if (query.proto != null) {
                if (!query.proto.equals(descriptor)) return null;
                score += 18; reasons.add("proto:" + query.proto);
            }
            if (query.returnType != null) {
                if (!query.returnType.equals(returnType)) return null;
                score += 12; reasons.add("returnType");
            }
            if (query.parameterTypes != null) {
                if (!query.parameterTypes.equals(parameterList)) return null;
                score += 12; reasons.add("parameterTypes");
            }
            if (query.requireStatic != null) {
                if (query.requireStatic.booleanValue() != isStatic()) return null;
                score += 8; reasons.add(query.requireStatic ? "static" : "non-static");
            }
            List<String> methodStrings = stringsRaw();
            for (String s : query.strings) {
                if (!methodStrings.contains(s)) return null;
                score += 16; reasons.add("string:" + s);
            }
            List<String> methodInvokes = invokesRaw();
            for (String invoke : query.invokes) {
                if (!methodInvokes.contains(invoke)) return null;
                score += 14; reasons.add("invoke:" + invoke);
            }
            for (String invokePart : query.invokeContains) {
                boolean matchedInvoke = false;
                for (String actualInvoke : methodInvokes) {
                    if (actualInvoke != null && actualInvoke.contains(invokePart)) { matchedInvoke = true; break; }
                }
                if (!matchedInvoke) return null;
                score += 12; reasons.add("invokeContains:" + invokePart);
            }
            if (query.containsString != null) {
                boolean matched = false;
                for (String s : methodStrings) if (s.contains(query.containsString)) { matched = true; break; }
                if (!matched) return null;
                score += 10; reasons.add("containsString:" + query.containsString);
            }
            if (!query.smaliContains.isEmpty()) {
                String smali = smali();
                if (smali == null) smali = "";
                for (String keyword : query.smaliContains) {
                    if (keyword == null || keyword.isEmpty()) continue;
                    if (!smali.contains(keyword)) return null;
                    score += 10; reasons.add("smaliContains:" + keyword);
                }
            }
            return score <= 0 ? null : new DexMethodHit(this, score, reasons);
        }
    }

    public static final class DexSearchResult {
        private final List<DexMethodHit> hits;
        DexSearchResult(List<DexMethodHit> hits) { this.hits = hits == null ? Collections.<DexMethodHit>emptyList() : hits; }
        public Object all() { return js(toListRaw()); }
        public Object hits() { return all(); }
        public DexMethodHit first() { return hits.isEmpty() ? null : hits.get(0); }
        public DexMethodHit best() { return first(); }
        public int size() { return hits.size(); }
        public Object toList() { return js(toListRaw()); }
        List<Map<String, Object>> toListRaw() {
            ArrayList<Map<String, Object>> out = new ArrayList<>();
            for (DexMethodHit hit : hits) out.add(hit.toMapRaw());
            return out;
        }
    }

    public static final class DexMethodHit {
        private final DexMethodView methodView;
        public final String className;
        public final String classDescriptor;
        public final String methodName;
        public final String descriptor;
        public final int score;
        private final List<String> reasonList;
        public final Object reasons;
        public final String sourcePath;
        public final String sourceEntry;
        public final String sourceOrigin;
        public final DexMethodView method;

        DexMethodHit(DexMethodView methodView, int score, List<String> reasons) {
            this.methodView = methodView;
            this.method = methodView;
            this.className = methodView.className;
            this.classDescriptor = methodView.classDescriptor;
            this.methodName = methodView.name;
            this.descriptor = methodView.descriptor;
            this.score = score;
            this.reasonList = reasons == null ? Collections.<String>emptyList() : new ArrayList<>(reasons);
            this.reasons = js(this.reasonList);
            DexSource source = methodView.unit == null ? null : methodView.unit.source;
            this.sourcePath = source == null ? null : source.path;
            this.sourceEntry = source == null ? null : source.entry;
            this.sourceOrigin = source == null ? null : source.origin;
        }

        public Executable toMethod(Object loader) throws Exception { return methodView.toMethod(loader); }
        public String smali() { return methodView.smali(); }
        public Object instructions() { return methodView.instructions(); }
        public Object strings() { return js(methodView.stringsRaw()); }
        public Object invokes() { return js(methodView.invokesRaw()); }

        public Object toMap() {
            return js(toMapRaw());
        }

        Map<String, Object> toMapRaw() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("className", className);
            out.put("classDescriptor", classDescriptor);
            out.put("methodName", methodName);
            out.put("name", methodName);
            out.put("proto", descriptor);
            out.put("descriptor", descriptor);
            out.put("path", sourcePath);
            out.put("sourcePath", sourcePath);
            out.put("sourceEntry", sourceEntry);
            out.put("sourceOrigin", sourceOrigin);
            out.put("score", score);
            out.put("reasons", reasonList);
            out.put("strings", methodView.stringsRaw());
            out.put("invokes", methodView.invokesRaw());
            String smali = methodView.smali();
            if (smali != null && smali.length() > maxSmaliChars) smali = smali.substring(0, maxSmaliChars);
            out.put("smaliHead", smali);
            return out;
        }
    }

    static final class DexUnit {
        final DexSource source;
        final DexFile dexFile;
        DexUnit(DexSource source, DexFile dexFile) { this.source = source; this.dexFile = dexFile; }
    }

    static final class DexSource {
        final String path;
        final String entry;
        final String type;
        @Nullable final ClassLoader loader;
        final String origin;
        DexSource(String path, String entry, String type) { this(path, entry, type, null, "unknown"); }
        DexSource(String path, String entry, String type, @Nullable ClassLoader loader, @Nullable String origin) {
            this.path = path;
            this.entry = entry;
            this.type = type;
            this.loader = loader;
            this.origin = origin == null ? "unknown" : origin;
        }
        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("path", path);
            map.put("entry", entry);
            map.put("type", type);
            map.put("origin", origin);
            map.put("loaderId", loader == null ? null : System.identityHashCode(loader));
            map.put("loader", loader == null ? null : String.valueOf(loader));
            return map;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof DexSource)) return false;
            DexSource o = (DexSource) other;
            return String.valueOf(path).equals(String.valueOf(o.path))
                    && String.valueOf(entry).equals(String.valueOf(o.entry))
                    && System.identityHashCode(loader) == System.identityHashCode(o.loader);
        }
        @Override public int hashCode() {
            int h = path == null ? 0 : path.hashCode();
            h = 31 * h + (entry == null ? 0 : entry.hashCode());
            h = 31 * h + System.identityHashCode(loader);
            return h;
        }
    }

    static final class DexSourceResolver {
        static List<DexSource> fromLoader(ClassLoader loader) {
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            collectFromReflection(loader, paths);
            collectFromString(String.valueOf(loader), paths);
            ArrayList<DexSource> out = new ArrayList<>();
            for (String path : paths) {
                if (path == null || path.trim().isEmpty()) continue;
                File file = new File(path.trim());
                if (!file.isFile()) continue;
                out.add(new DexSource(file.getAbsolutePath(), null, sourceType(file), loader, "loader-reflection"));
            }
            return out;
        }

        private static void collectFromString(String text, Set<String> out) {
            if (text == null) return;
            Matcher m = QUOTED_PATH.matcher(text);
            while (m.find()) out.add(m.group(1));
            String[] chunks = text.split(":" );
            for (String chunk : chunks) {
                String clean = chunk.replace('[', ' ').replace(']', ' ').replace(',', ' ').trim();
                int idx = clean.indexOf("/data/");
                if (idx >= 0) clean = clean.substring(idx).trim();
                if (looksLikeDexSource(clean)) out.add(clean);
            }
        }

        private static void collectFromReflection(ClassLoader loader, Set<String> out) {
            if (loader == null) return;
            try {
                java.lang.reflect.Field pathListField = findField(loader.getClass(), "pathList");
                Object pathList = pathListField.get(loader);
                java.lang.reflect.Field dexElementsField = findField(pathList.getClass(), "dexElements");
                Object[] elements = (Object[]) dexElementsField.get(pathList);
                if (elements == null) return;
                for (Object element : elements) {
                    addFileField(element, "path", out);
                    addFileField(element, "file", out);
                    addFileField(element, "zip", out);
                    Object dexFile = getFieldValue(element, "dexFile");
                    if (dexFile != null) {
                        addStringField(dexFile, "mFileName", out);
                        addFileField(dexFile, "mFile", out);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private static void addFileField(Object target, String name, Set<String> out) {
            Object value = getFieldValue(target, name);
            if (value instanceof File) out.add(((File) value).getAbsolutePath());
            else if (value != null && looksLikeDexSource(String.valueOf(value))) out.add(String.valueOf(value));
        }

        private static void addStringField(Object target, String name, Set<String> out) {
            Object value = getFieldValue(target, name);
            if (value != null && looksLikeDexSource(String.valueOf(value))) out.add(String.valueOf(value));
        }

        private static Object getFieldValue(Object target, String name) {
            if (target == null) return null;
            try {
                java.lang.reflect.Field f = findField(target.getClass(), name);
                return f.get(target);
            } catch (Throwable ignored) { return null; }
        }

        private static java.lang.reflect.Field findField(Class<?> cls, String name) throws NoSuchFieldException {
            Class<?> c = cls;
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
            }
            throw new NoSuchFieldException(name);
        }

        private static boolean looksLikeDexSource(String path) {
            if (path == null) return false;
            String clean = path.trim().toLowerCase(Locale.ROOT);
            return clean.endsWith(".apk") || clean.endsWith(".dex") || clean.endsWith(".jar") || clean.endsWith(".zip");
        }
    }

    static final class Query {
        ClassLoader loader;
        DexFileView file;
        String path;
        List<String> paths = new ArrayList<>();
        String classPrefix;
        String className;
        String methodName;
        String proto;
        String returnType;
        List<String> parameterTypes;
        Boolean requireStatic;
        String containsString;
        List<String> strings = new ArrayList<>();
        List<String> invokes = new ArrayList<>();
        List<String> invokeContains = new ArrayList<>();
        List<String> smaliContains = new ArrayList<>();
        List<String> excludeClassPrefixes = new ArrayList<>();
        boolean verbose = false;
        int progressEveryClasses = 100;
        int progressEveryMethods = 0;
        int minScore = 1;
        int limit = 0;

        boolean excludedClass(String className) {
            if (className == null || excludeClassPrefixes == null || excludeClassPrefixes.isEmpty()) return false;
            for (String prefix : excludeClassPrefixes) {
                if (prefix != null && !prefix.isEmpty() && className.startsWith(prefix)) return true;
            }
            return false;
        }

        boolean hasAnyCondition() {
            return classPrefix != null
                    || className != null
                    || methodName != null
                    || proto != null
                    || returnType != null
                    || parameterTypes != null
                    || requireStatic != null
                    || containsString != null
                    || (strings != null && !strings.isEmpty())
                    || (invokes != null && !invokes.isEmpty())
                    || (invokeContains != null && !invokeContains.isEmpty())
                    || (smaliContains != null && !smaliContains.isEmpty());
        }

        String summary() {
            return "path=" + path + " paths=" + paths + " strings=" + strings + " invokes=" + invokes
                    + " invokeContains=" + invokeContains + " smaliContains=" + smaliContains
                    + " proto=" + proto + " returnType=" + returnType + " params=" + parameterTypes + " static=" + requireStatic
                    + " classPrefix=" + classPrefix + " className=" + className + " exclude=" + excludeClassPrefixes
                    + " minScore=" + minScore + " limit=" + limit;
        }

        static Query from(Object raw) {
            Query q = new Query();
            Object obj = unwrap(raw);
            if (obj instanceof Query) return (Query) obj;
            if (obj instanceof DexFileView) { q.file = (DexFileView) obj; return q; }
            if (obj instanceof Scriptable) {
                Scriptable s = (Scriptable) obj;
                Object loader = get(s, "loader");
                if (loader instanceof ClassLoader) q.loader = (ClassLoader) loader;
                Object file = get(s, "file");
                if (!(file instanceof DexFileView)) file = get(s, "dexFile");
                if (file instanceof DexFileView) q.file = (DexFileView) file;
                q.path = asString(get(s, "path"));
                if (q.path == null) q.path = asString(get(s, "dexPath"));
                if (q.path == null && !(file instanceof DexFileView)) q.path = asString(file);
                q.paths = asStringList(get(s, "paths"));
                if (q.paths.isEmpty()) q.paths = asStringList(get(s, "dexPaths"));
                fillQueryFromAccessors(q, new QueryAccess() {
                    @Override public Object get(String name) { return DexApiFacade.get(s, name); }
                });
                return normalizeQuery(q);
            }
            if (obj instanceof Map) {
                final Map<?, ?> m = (Map<?, ?>) obj;
                Object loader = mapGet(m, "loader");
                if (loader instanceof ClassLoader) q.loader = (ClassLoader) loader;
                Object file = mapGet(m, "file");
                if (!(file instanceof DexFileView)) file = mapGet(m, "dexFile");
                if (file instanceof DexFileView) q.file = (DexFileView) file;
                q.path = asString(mapGet(m, "path"));
                if (q.path == null) q.path = asString(mapGet(m, "dexPath"));
                if (q.path == null && !(file instanceof DexFileView)) q.path = asString(file);
                q.paths = asStringList(mapGet(m, "paths"));
                if (q.paths.isEmpty()) q.paths = asStringList(mapGet(m, "dexPaths"));
                fillQueryFromAccessors(q, new QueryAccess() {
                    @Override public Object get(String name) { return mapGet(m, name); }
                });
                return normalizeQuery(q);
            }
            return normalizeQuery(q);
        }

        private static Query normalizeQuery(Query q) {
            if (q.proto != null && q.proto.trim().isEmpty()) q.proto = null;
            if (q.progressEveryClasses <= 0) q.progressEveryClasses = 100;
            if (q.progressEveryMethods < 0) q.progressEveryMethods = 0;
            return q;
        }

        private interface QueryAccess { Object get(String name); }

        private static void fillQueryFromAccessors(Query q, QueryAccess access) {
            q.classPrefix = asString(access.get("classPrefix"));
            q.className = asString(access.get("className"));
            if (q.className == null) q.className = asString(access.get("class"));
            q.methodName = asString(access.get("name"));
            if (q.methodName == null) q.methodName = asString(access.get("methodName"));
            q.proto = asString(access.get("proto"));
            if (q.proto == null) q.proto = asString(access.get("descriptor"));
            String ret = asString(access.get("returnType"));
            if (ret != null) q.returnType = DexProtoUtils.toDescriptor(ret);
            Object params = access.get("parameterTypes");
            if (params == null) params = access.get("parameters");
            if (params != null) q.parameterTypes = DexProtoUtils.toDescriptors(asStringList(params));
            q.strings = asStringList(access.get("strings"));
            q.invokes = asStringList(access.get("invokes"));
            q.invokeContains = asStringList(access.get("invokeContains"));
            if (q.invokeContains.isEmpty()) q.invokeContains = asStringList(access.get("invokesContains"));
            q.smaliContains = asStringList(access.get("smaliContains"));
            if (q.smaliContains.isEmpty()) q.smaliContains = asStringList(access.get("smaliKeywords"));
            if (q.smaliContains.isEmpty()) q.smaliContains = asStringList(access.get("smaliKeyword"));
            if (q.smaliContains.isEmpty()) q.smaliContains = asStringList(access.get("smali"));
            q.containsString = asString(access.get("containsString"));
            if (q.containsString == null) q.containsString = asString(access.get("stringContains"));
            q.excludeClassPrefixes = asStringList(access.get("excludeClassPrefixes"));
            if (q.excludeClassPrefixes.isEmpty()) q.excludeClassPrefixes = asStringList(access.get("excludeClassPrefix"));
            q.verbose = asBoolean(access.get("verbose"), q.verbose);
            q.progressEveryClasses = asInt(access.get("progressEveryClasses"), q.progressEveryClasses);
            q.progressEveryMethods = asInt(access.get("progressEveryMethods"), q.progressEveryMethods);
            Object minScore = access.get("minScore");
            if (minScore instanceof Number) q.minScore = ((Number) minScore).intValue();
            Object limit = access.get("limit");
            if (limit instanceof Number) q.limit = ((Number) limit).intValue();
            List<String> modifiers = asStringList(access.get("modifiers"));
            for (String m : modifiers) if ("static".equalsIgnoreCase(m)) q.requireStatic = Boolean.TRUE;
            Object staticValue = access.get("static");
            if (staticValue instanceof Boolean) q.requireStatic = (Boolean) staticValue;
        }
    }

    static final class DexProtoUtils {
        static String proto(Method method) {
            StringBuilder sb = new StringBuilder("(");
            for (CharSequence p : method.getParameterTypes()) sb.append(p);
            sb.append(')').append(method.getReturnType());
            return sb.toString();
        }

        static String toDescriptor(String type) {
            if (type == null) return null;
            String t = type.trim();
            if (t.isEmpty()) return t;
            if (t.length() == 1 && "ZBCSIJFDV".contains(t)) return t;
            if (t.startsWith("[")) return t.replace('.', '/');
            if (t.startsWith("L") && t.endsWith(";")) return t;
            switch (t) {
                case "boolean": return "Z";
                case "byte": return "B";
                case "char": return "C";
                case "short": return "S";
                case "int": return "I";
                case "long": return "J";
                case "float": return "F";
                case "double": return "D";
                case "void": return "V";
                default: return "L" + t.replace('.', '/') + ";";
            }
        }

        static List<String> toDescriptors(List<String> types) {
            ArrayList<String> out = new ArrayList<>();
            for (String t : types) out.add(toDescriptor(t));
            return out;
        }

        static String toJavaName(String descriptor) {
            if (descriptor == null) return "";
            if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
                return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
            }
            return descriptor.replace('/', '.');
        }

        static Class<?>[] toClassArray(List<String> descriptors, ClassLoader loader) throws ClassNotFoundException {
            Class<?>[] out = new Class<?>[descriptors.size()];
            for (int i = 0; i < descriptors.size(); i++) out[i] = toClass(descriptors.get(i), loader);
            return out;
        }

        static Class<?> toClass(String descriptor, ClassLoader loader) throws ClassNotFoundException {
            switch (descriptor) {
                case "Z": return Boolean.TYPE;
                case "B": return Byte.TYPE;
                case "C": return Character.TYPE;
                case "S": return Short.TYPE;
                case "I": return Integer.TYPE;
                case "J": return Long.TYPE;
                case "F": return Float.TYPE;
                case "D": return Double.TYPE;
                case "V": return Void.TYPE;
                default:
                    if (descriptor.startsWith("[")) return Class.forName(descriptor.replace('/', '.'), false, loader);
                    return Class.forName(toJavaName(descriptor), false, loader);
            }
        }
    }

    static final class DexSmaliPrinter {
        static String method(Method method, int maxChars) {
            StringBuilder sb = new StringBuilder();
            sb.append(".method ").append(accessFlags(method.getAccessFlags())).append(' ')
                    .append(method.getName()).append(protoInline(method)).append('\n');
            MethodImplementation impl = method.getImplementation();
            if (impl == null) {
                sb.append(".end method\n");
                return sb.toString();
            }
            sb.append("    .registers ").append(impl.getRegisterCount()).append('\n');
            int offset = 0;
            for (Instruction ins : impl.getInstructions()) {
                String ref = ins instanceof ReferenceInstruction ? referenceString(((ReferenceInstruction) ins).getReference()) : null;
                sb.append("    #@") .append(Integer.toHexString(offset)).append("  ").append(ins.getOpcode().name);
                if (ref != null) sb.append(' ').append(ref);
                sb.append('\n');
                offset += ins.getCodeUnits();
                if (sb.length() > maxChars) { sb.append("    # ... truncated ...\n"); break; }
            }
            sb.append(".end method\n");
            return sb.toString();
        }

        static String accessFlags(int flags) {
            ArrayList<String> out = new ArrayList<>();
            for (AccessFlags flag : AccessFlags.getAccessFlagsForMethod(flags)) {
                if (flag.isSet(flags)) out.add(flag.toString());
            }
            return join(out, " ");
        }

        private static String protoInline(Method method) { return DexProtoUtils.proto(method); }
        private static String join(List<String> list, String sep) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) { if (i > 0) sb.append(sep); sb.append(list.get(i)); }
            return sb.toString();
        }
    }

    static String referenceString(Reference ref) {
        if (ref == null) return null;
        try { return ReferenceUtil.getReferenceString(ref); }
        catch (Throwable ignored) { return String.valueOf(ref); }
    }

    static Object mapGet(Map<?, ?> map, String name) {
        if (map == null || name == null) return null;
        Object value = map.get(name);
        if (value == null) value = map.get(String.valueOf(name));
        if (value == Undefined.instance || value == Scriptable.NOT_FOUND) return null;
        return unwrap(value);
    }

    static Object get(Scriptable s, String name) {
        Object value = ScriptableObject.getProperty(s, name);
        if (value == Scriptable.NOT_FOUND || value == Undefined.instance) return null;
        return unwrap(value);
    }

    static long asLong(Object value, long fallback) {
        Object v = unwrap(value);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return fallback;
        try { return Long.parseLong(String.valueOf(v)); } catch (Throwable ignored) { return fallback; }
    }

    static long clampLong(long value, long min, long max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    static int clampInt(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    static int asInt(Object value, int fallback) {
        Object v = unwrap(value);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return fallback;
        try { return Integer.parseInt(String.valueOf(v)); } catch (Throwable ignored) { return fallback; }
    }

    static boolean asBoolean(Object value, boolean fallback) {
        Object v = unwrap(value);
        if (v instanceof Boolean) return (Boolean) v;
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return fallback;
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        return fallback;
    }

    static String asString(Object value) {
        Object v = unwrap(value);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    static List<String> asStringList(Object value) {
        Object v = unwrap(value);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return new ArrayList<>();
        ArrayList<String> out = new ArrayList<>();
        if (v instanceof NativeArray) {
            NativeArray arr = (NativeArray) v;
            Object[] ids = arr.getIds();
            if (ids != null && ids.length > 0) {
                for (Object id : ids) {
                    Object item;
                    if (id instanceof Number) item = arr.get(((Number) id).intValue(), arr);
                    else item = arr.get(String.valueOf(id), arr);
                    String s = asString(item);
                    if (s != null) out.add(s);
                }
            } else {
                long len = arr.getLength();
                long max = Math.min(len, 10000L);
                for (int i = 0; i < max; i++) {
                    Object item = arr.get(i, arr);
                    String s = asString(item);
                    if (s != null) out.add(s);
                }
            }
            return out;
        }
        if (v != null && v.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < len; i++) {
                String s = asString(java.lang.reflect.Array.get(v, i));
                if (s != null) out.add(s);
            }
            return out;
        }
        if (v instanceof Iterable) {
            for (Object item : (Iterable<?>) v) {
                String s = asString(item);
                if (s != null) out.add(s);
            }
            return out;
        }
        String s = asString(v);
        if (s != null) out.add(s);
        return out;
    }

    static ClassLoader asClassLoader(Object value, ClassLoader fallback) {
        Object v = unwrap(value);
        if (v instanceof ClassLoader) return (ClassLoader) v;
        if (v instanceof Class<?>) {
            ClassLoader loader = ((Class<?>) v).getClassLoader();
            return loader == null ? fallback : loader;
        }
        if (v instanceof Executable) {
            ClassLoader loader = ((Executable) v).getDeclaringClass().getClassLoader();
            return loader == null ? fallback : loader;
        }
        return fallback;
    }

    static Object unwrap(Object value) {
        if (value instanceof Wrapper) return ((Wrapper) value).unwrap();
        return value;
    }
}
