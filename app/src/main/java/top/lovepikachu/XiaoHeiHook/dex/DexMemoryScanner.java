package top.lovepikachu.XiaoHeiHook.dex;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native in-process memory scanner for decrypted dex images.
 *
 * This scans readable mappings in the current app process, searches for dex magic,
 * validates dex headers, dumps plausible dex images to code_cache, and lets dexlib2
 * parse those dumped files through DexApiFacade.fromMemory().
 */
public final class DexMemoryScanner {
    private static final String TAG = "XHH-DexMemory";
    private static final int DEFAULT_MAX_REGION_BYTES = 256 * 1024 * 1024;
    private static final int DEFAULT_MAX_DUMP_BYTES = 256 * 1024 * 1024;
    private static final int DEFAULT_MAX_DUMP_COUNT = 32;

    private static volatile boolean loadTried = false;
    private static volatile boolean nativeAvailable = false;
    @Nullable private static volatile Throwable nativeLoadError;
    private static final Object LAST_LOCK = new Object();
    private static List<Map<String, Object>> lastSources = Collections.emptyList();

    private DexMemoryScanner() {}

    private static void ensureLoaded() {
        if (loadTried) return;
        synchronized (DexMemoryScanner.class) {
            if (loadTried) return;
            loadTried = true;
            try {
                System.loadLibrary("xhh_dexscan");
                nativeAvailable = true;
                Log.i(TAG, "native library loaded: xhh_dexscan");
            } catch (Throwable t) {
                nativeLoadError = t;
                nativeAvailable = false;
                Log.w(TAG, "native library load failed", t);
            }
        }
    }

    public static boolean isAvailable() {
        ensureLoaded();
        return nativeAvailable;
    }

    @Nullable
    public static Throwable getLoadError() {
        ensureLoaded();
        return nativeLoadError;
    }

    @NonNull
    public static List<Map<String, Object>> getLastSources() {
        synchronized (LAST_LOCK) {
            return new ArrayList<>(lastSources);
        }
    }

    private static void setLastSources(@Nullable List<Map<String, Object>> sources) {
        ArrayList<Map<String, Object>> copy = new ArrayList<>();
        if (sources != null) {
            for (Map<String, Object> source : sources) {
                if (source == null) continue;
                copy.add(new LinkedHashMap<>(source));
            }
        }
        synchronized (LAST_LOCK) {
            lastSources = copy;
        }
    }

    @NonNull
    public static List<Map<String, Object>> scanAndDump(
            @NonNull String outputDir,
            int maxRegionBytes,
            int maxDumpBytes,
            int maxDumpCount,
            boolean includeAnonymous,
            boolean includeFileBacked,
            boolean relaxed,
            @Nullable String[] requireAsciiContains,
            boolean requireAllAsciiContains
    ) {
        ensureLoaded();
        if (!nativeAvailable) {
            throw new IllegalStateException("native dex memory scanner unavailable", nativeLoadError);
        }
        File dir = new File(outputDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建内存 dex dump 目录: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new IllegalStateException("内存 dex dump 目录不是目录: " + dir.getAbsolutePath());
        }
        if (maxRegionBytes <= 0) maxRegionBytes = DEFAULT_MAX_REGION_BYTES;
        if (maxDumpBytes <= 0) maxDumpBytes = DEFAULT_MAX_DUMP_BYTES;
        if (maxDumpCount <= 0) maxDumpCount = DEFAULT_MAX_DUMP_COUNT;
        ArrayList<Map<String, Object>> result = nativeScanAndDump(
                dir.getAbsolutePath(),
                maxRegionBytes,
                maxDumpBytes,
                maxDumpCount,
                includeAnonymous,
                includeFileBacked,
                relaxed,
                requireAsciiContains == null ? new String[0] : requireAsciiContains,
                requireAllAsciiContains
        );
        setLastSources(result);
        return result == null ? Collections.<Map<String, Object>>emptyList() : result;
    }


    /**
     * Raw/salvage dump mode: keep dex-like candidates even when map/string/class
     * validation would reject them. This is useful for packers whose transient
     * business dex cannot be parsed directly by dexlib2 but can be inspected or
     * repaired by external dump tools.
     */
    @NonNull
    public static List<Map<String, Object>> scanAndDumpRaw(
            @NonNull String outputDir,
            int maxRegionBytes,
            int maxDumpBytes,
            int maxDumpCount,
            boolean includeAnonymous,
            boolean includeFileBacked,
            boolean includeInvalid,
            @Nullable String[] requireAsciiContains,
            boolean requireAllAsciiContains,
            int rawWindowBytes
    ) {
        ensureLoaded();
        if (!nativeAvailable) {
            throw new IllegalStateException("native dex memory scanner unavailable", nativeLoadError);
        }
        File dir = new File(outputDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 raw dex dump 目录: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new IllegalStateException("raw dex dump 目录不是目录: " + dir.getAbsolutePath());
        }
        if (maxRegionBytes <= 0) maxRegionBytes = DEFAULT_MAX_REGION_BYTES;
        if (maxDumpBytes <= 0) maxDumpBytes = DEFAULT_MAX_DUMP_BYTES;
        if (maxDumpCount <= 0) maxDumpCount = DEFAULT_MAX_DUMP_COUNT;
        if (rawWindowBytes <= 0) rawWindowBytes = 128 * 1024 * 1024;
        ArrayList<Map<String, Object>> result = nativeScanAndDumpRaw(
                dir.getAbsolutePath(),
                maxRegionBytes,
                maxDumpBytes,
                maxDumpCount,
                includeAnonymous,
                includeFileBacked,
                includeInvalid,
                requireAsciiContains == null ? new String[0] : requireAsciiContains,
                requireAllAsciiContains,
                rawWindowBytes
        );
        setLastSources(result);
        return result == null ? Collections.<Map<String, Object>>emptyList() : result;
    }


    @NonNull
    public static List<Map<String, Object>> scanStrings(
            @NonNull String outputDir,
            int maxRegionBytes,
            int maxHits,
            int contextBytes,
            int windowBytes,
            boolean includeAnonymous,
            boolean includeFileBacked,
            boolean requireAll,
            boolean dumpWindows,
            @Nullable String[] needles
    ) {
        ensureLoaded();
        if (!nativeAvailable) {
            throw new IllegalStateException("native dex memory scanner unavailable", nativeLoadError);
        }
        if (needles == null || needles.length == 0) {
            throw new IllegalArgumentException("needles 不能为空");
        }
        File dir = new File(outputDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建内存 string dump 目录: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new IllegalStateException("内存 string dump 目录不是目录: " + dir.getAbsolutePath());
        }
        if (maxRegionBytes <= 0) maxRegionBytes = DEFAULT_MAX_REGION_BYTES;
        if (maxHits <= 0) maxHits = 64;
        if (contextBytes < 0) contextBytes = 0;
        if (contextBytes > 4096) contextBytes = 4096;
        if (windowBytes < 0) windowBytes = 0;
        if (windowBytes > 16 * 1024 * 1024) windowBytes = 16 * 1024 * 1024;
        ArrayList<Map<String, Object>> result = nativeScanStrings(
                dir.getAbsolutePath(),
                maxRegionBytes,
                maxHits,
                contextBytes,
                windowBytes,
                includeAnonymous,
                includeFileBacked,
                requireAll,
                dumpWindows,
                needles
        );
        return result == null ? Collections.<Map<String, Object>>emptyList() : result;
    }



    /**
     * Dump dex images through live ART DexFile cookies collected from ClassLoader
     * DexPathList elements. This follows the BlackDex-style cookie route and does
     * not depend on finding dex magic in /proc/self/maps.
     */
    @NonNull
    public static List<Map<String, Object>> dumpFromCookies(
            @NonNull String outputDir,
            @Nullable long[] cookieValues,
            int maxDexBytes,
            int maxDumpCount,
            boolean verbose
    ) {
        ensureLoaded();
        if (!nativeAvailable) {
            throw new IllegalStateException("native dex memory scanner unavailable", nativeLoadError);
        }
        File dir = new File(outputDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 cookie dex dump 目录: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new IllegalStateException("cookie dex dump 目录不是目录: " + dir.getAbsolutePath());
        }
        if (maxDexBytes <= 0) maxDexBytes = DEFAULT_MAX_DUMP_BYTES;
        if (maxDumpCount <= 0) maxDumpCount = DEFAULT_MAX_DUMP_COUNT;
        ArrayList<Map<String, Object>> result = nativeDumpFromCookies(
                dir.getAbsolutePath(),
                cookieValues == null ? new long[0] : cookieValues,
                maxDexBytes,
                maxDumpCount,
                verbose
        );
        setLastSources(result);
        return result == null ? Collections.<Map<String, Object>>emptyList() : result;
    }


    @NonNull
    private static native ArrayList<Map<String, Object>> nativeDumpFromCookies(
            @NonNull String outputDir,
            @NonNull long[] cookieValues,
            int maxDexBytes,
            int maxDumpCount,
            boolean verbose
    );

    @NonNull
    private static native ArrayList<Map<String, Object>> nativeScanAndDump(
            @NonNull String outputDir,
            int maxRegionBytes,
            int maxDumpBytes,
            int maxDumpCount,
            boolean includeAnonymous,
            boolean includeFileBacked,
            boolean relaxed,
            @NonNull String[] requireAsciiContains,
            boolean requireAllAsciiContains
    );

    @NonNull
    private static native ArrayList<Map<String, Object>> nativeScanAndDumpRaw(
            @NonNull String outputDir,
            int maxRegionBytes,
            int maxDumpBytes,
            int maxDumpCount,
            boolean includeAnonymous,
            boolean includeFileBacked,
            boolean includeInvalid,
            @NonNull String[] requireAsciiContains,
            boolean requireAllAsciiContains,
            int rawWindowBytes
    );


    @NonNull
    private static native ArrayList<Map<String, Object>> nativeScanStrings(
            @NonNull String outputDir,
            int maxRegionBytes,
            int maxHits,
            int contextBytes,
            int windowBytes,
            boolean includeAnonymous,
            boolean includeFileBacked,
            boolean requireAll,
            boolean dumpWindows,
            @NonNull String[] needles
    );

}
