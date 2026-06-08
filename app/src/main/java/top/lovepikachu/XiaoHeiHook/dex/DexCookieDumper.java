package top.lovepikachu.XiaoHeiHook.dex;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DexFile-cookie based dumper inspired by BlackDex/FART style pipelines.
 *
 * The Java side only collects dalvik.system.DexFile.mCookie values from live
 * ClassLoaders. The native side treats those cookies as ART DexFile pointers
 * and probes begin_/size_ fields instead of relying on /proc/self/maps dex magic.
 */
final class DexCookieDumper {
    private static final String TAG = "XHH-DexCookie";

    private DexCookieDumper() {}

    @NonNull
    static Result dump(@Nullable ClassLoader rootLoader,
                       @NonNull String outputDir,
                       int maxDexBytes,
                       int maxDumpCount,
                       boolean includeParents,
                       boolean includeThreadContext,
                       boolean verbose) {
        File dir = new File(outputDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 cookie dex dump 目录: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new IllegalStateException("cookie dex dump 输出路径不是目录: " + dir.getAbsolutePath());
        }

        ArrayList<ClassLoader> loaders = collectLoaders(rootLoader, includeParents, includeThreadContext);
        ArrayList<Map<String, Object>> cookieInfos = new ArrayList<>();
        LinkedHashSet<Long> cookieValues = new LinkedHashSet<>();
        int dexFileObjects = 0;

        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            if (verbose) Log.i(TAG, "collect loader=" + loader + " class=" + loader.getClass().getName());
            ArrayList<Object> dexFiles = new ArrayList<>();
            collectDexFilesFromLoader(loader, dexFiles, verbose);
            for (Object dexFile : dexFiles) {
                if (dexFile == null) continue;
                dexFileObjects++;
                String fileName = asString(readFieldQuietly(dexFile, "mFileName"));
                Object cookie = readFieldQuietly(dexFile, "mCookie");
                if (cookie == null) cookie = readFieldQuietly(dexFile, "mInternalCookie");
                long[] longs = cookieToLongs(cookie);
                LinkedHashMap<String, Object> info = new LinkedHashMap<>();
                info.put("loader", String.valueOf(loader));
                info.put("dexFile", String.valueOf(dexFile));
                info.put("fileName", fileName);
                info.put("cookieClass", cookie == null ? null : cookie.getClass().getName());
                info.put("cookieCount", longs.length);
                ArrayList<String> hex = new ArrayList<>();
                for (long v : longs) {
                    if (v != 0L) {
                        cookieValues.add(v);
                        hex.add("0x" + Long.toHexString(v));
                    }
                }
                info.put("cookies", hex);
                cookieInfos.add(info);
            }
        }

        long[] unique = new long[cookieValues.size()];
        int i = 0;
        for (Long value : cookieValues) unique[i++] = value == null ? 0L : value;
        if (verbose) Log.i(TAG, "cookie collect loaders=" + loaders.size() + " dexFileObjects=" + dexFileObjects + " cookieValues=" + unique.length);

        List<Map<String, Object>> dumps = DexMemoryScanner.dumpFromCookies(dir.getAbsolutePath(), unique, maxDexBytes, maxDumpCount, verbose);
        return new Result(loaders.size(), dexFileObjects, unique.length, cookieInfos, dumps);
    }

    @NonNull
    private static ArrayList<ClassLoader> collectLoaders(@Nullable ClassLoader rootLoader,
                                                          boolean includeParents,
                                                          boolean includeThreadContext) {
        ArrayList<ClassLoader> out = new ArrayList<>();
        Set<ClassLoader> seen = Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
        addLoader(out, seen, rootLoader, includeParents);
        if (includeThreadContext) addLoader(out, seen, Thread.currentThread().getContextClassLoader(), includeParents);
        addLoader(out, seen, DexCookieDumper.class.getClassLoader(), includeParents);
        addLoader(out, seen, ClassLoader.getSystemClassLoader(), includeParents);
        return out;
    }

    private static void addLoader(ArrayList<ClassLoader> out, Set<ClassLoader> seen, ClassLoader loader, boolean includeParents) {
        ClassLoader cur = loader;
        while (cur != null) {
            if (!seen.contains(cur)) {
                seen.add(cur);
                out.add(cur);
            }
            if (!includeParents) break;
            cur = cur.getParent();
        }
    }

    private static void collectDexFilesFromLoader(ClassLoader loader, ArrayList<Object> out, boolean verbose) {
        Object pathList = readFieldQuietly(loader, "pathList");
        if (pathList == null) {
            if (verbose) Log.i(TAG, "loader has no pathList: " + loader);
            return;
        }
        collectDexFilesFromPathList(pathList, out, verbose);
    }

    private static void collectDexFilesFromPathList(Object pathList, ArrayList<Object> out, boolean verbose) {
        String[] elementFields = new String[] { "dexElements", "pathElements" };
        for (String elementField : elementFields) {
            Object elements = readFieldQuietly(pathList, elementField);
            if (elements == null || !elements.getClass().isArray()) continue;
            int len = Array.getLength(elements);
            if (verbose) Log.i(TAG, "pathList " + elementField + " length=" + len);
            for (int i = 0; i < len; i++) {
                Object element = Array.get(elements, i);
                if (element == null) continue;
                Object dexFile = readFieldQuietly(element, "dexFile");
                if (dexFile == null) dexFile = readFieldQuietly(element, "mDexFile");
                if (dexFile != null) out.add(dexFile);
            }
        }
    }

    @Nullable
    private static Object readFieldQuietly(Object target, String name) {
        if (target == null || name == null) return null;
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    @NonNull
    private static long[] cookieToLongs(@Nullable Object cookie) {
        if (cookie == null) return new long[0];
        if (cookie instanceof Number) return new long[] { ((Number) cookie).longValue() };
        Class<?> cls = cookie.getClass();
        if (!cls.isArray()) return new long[0];
        int len = Array.getLength(cookie);
        long[] out = new long[len];
        for (int i = 0; i < len; i++) {
            Object item = Array.get(cookie, i);
            if (item instanceof Number) out[i] = ((Number) item).longValue();
        }
        return out;
    }

    @Nullable
    private static String asString(@Nullable Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    static final class Result {
        final int loaderCount;
        final int dexFileObjectCount;
        final int cookieValueCount;
        final List<Map<String, Object>> cookieInfos;
        final List<Map<String, Object>> dumps;

        Result(int loaderCount,
               int dexFileObjectCount,
               int cookieValueCount,
               List<Map<String, Object>> cookieInfos,
               List<Map<String, Object>> dumps) {
            this.loaderCount = loaderCount;
            this.dexFileObjectCount = dexFileObjectCount;
            this.cookieValueCount = cookieValueCount;
            this.cookieInfos = cookieInfos == null ? Collections.<Map<String, Object>>emptyList() : cookieInfos;
            this.dumps = dumps == null ? Collections.<Map<String, Object>>emptyList() : dumps;
        }
    }
}
