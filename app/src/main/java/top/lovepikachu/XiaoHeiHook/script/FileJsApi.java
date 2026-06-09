package top.lovepikachu.XiaoHeiHook.script;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** JavaScript facade exposed as xhh.fs. */
public final class FileJsApi {
    private final ScriptPathResolver resolver;
    private final ScriptAssetManager assetManager;

    public FileJsApi(@NonNull ScriptPathResolver resolver, @NonNull ScriptAssetManager assetManager) {
        this.resolver = resolver;
        this.assetManager = assetManager;
    }

    public Object appDirs(Object context) {
        return TargetAppPathHelper.appDirs(context);
    }

    public String join(Object... parts) {
        if (parts == null || parts.length == 0) return "";
        File current = null;
        for (Object partObj : parts) {
            Object raw = unwrap(partObj);
            if (raw == null || raw == Undefined.instance || raw == Scriptable.NOT_FOUND) {
                throw new IllegalArgumentException("join path part 不能为空");
            }
            String part = String.valueOf(raw);
            if (part.isEmpty()) continue;
            current = current == null ? new File(part) : new File(current, part);
        }
        return current == null ? "" : current.getPath();
    }

    public boolean exists(String path) { return new File(requirePath(path)).exists(); }
    public boolean isFile(String path) { return new File(requirePath(path)).isFile(); }
    public boolean isDirectory(String path) { return new File(requirePath(path)).isDirectory(); }

    public boolean mkdirs(String path) throws IOException {
        return FileCopyUtils.mkdirs(new File(requirePath(path)));
    }

    public String readText(String path) throws IOException { return readText(path, "UTF-8", null); }
    public String readText(String path, String charset) throws IOException { return readText(path, charset, null); }
    public String readText(String path, String charset, Object options) throws IOException {
        byte[] bytes = FileCopyUtils.readAll(new File(requirePath(path)), optionMaxBytes(options));
        return new String(bytes, charset(charset));
    }

    public Object writeText(String path, String text) throws IOException { return writeText(path, text, "UTF-8"); }
    public Object writeText(String path, String text, String charset) throws IOException {
        byte[] bytes = (text == null ? "" : text).getBytes(charset(charset));
        File file = new File(requirePath(path));
        FileCopyUtils.writeAll(file, bytes, false);
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("path", file.getAbsolutePath());
        out.put("bytes", bytes.length);
        out.put("append", false);
        return JsApiValueNormalizer.toJs(out);
    }

    public Object appendText(String path, String text) throws IOException { return appendText(path, text, "UTF-8"); }
    public Object appendText(String path, String text, String charset) throws IOException {
        byte[] bytes = (text == null ? "" : text).getBytes(charset(charset));
        File file = new File(requirePath(path));
        FileCopyUtils.writeAll(file, bytes, true);
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("path", file.getAbsolutePath());
        out.put("bytes", bytes.length);
        out.put("append", true);
        return JsApiValueNormalizer.toJs(out);
    }

    public Object readBytes(String path) throws IOException { return readBytes(path, null); }
    public Object readBytes(String path, Object options) throws IOException {
        return JsApiValueNormalizer.toJs(FileCopyUtils.readAll(new File(requirePath(path)), optionMaxBytes(options)));
    }

    public Object writeBytes(String path, Object bytes) throws IOException {
        byte[] data = toByteArray(bytes);
        File file = new File(requirePath(path));
        FileCopyUtils.writeAll(file, data, false);
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("path", file.getAbsolutePath());
        out.put("bytes", data.length);
        return JsApiValueNormalizer.toJs(out);
    }

    public Object copy(String src, String dst) throws IOException { return copy(src, dst, null); }
    public Object copy(String src, String dst, Object options) throws IOException {
        File source = new File(requirePath(src));
        File target = new File(requirePath(dst));
        boolean overwrite = optionBoolean(options, "overwrite", true);
        boolean existed = target.exists();
        long bytes = FileCopyUtils.copy(source, target, overwrite);
        boolean skipped = existed && !overwrite;
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("source", source.getAbsolutePath());
        out.put("path", target.getAbsolutePath());
        out.put("bytes", skipped ? target.length() : bytes);
        out.put("copied", !skipped);
        out.put("skipped", skipped);
        out.put("overwritten", existed && !skipped);
        return JsApiValueNormalizer.toJs(out);
    }

    public String scriptRoot() { return resolver.scriptRoot().getAbsolutePath(); }
    public String scriptDir() { return resolver.scriptDir().getAbsolutePath(); }
    public String assetsDir() { return resolver.assetsDir().getAbsolutePath(); }

    public String assetPath(String relativePath) throws IOException {
        return assetManager.assetPath(relativePath, false).getAbsolutePath();
    }

    public String readAssetText(String relativePath) throws IOException { return readAssetText(relativePath, "UTF-8", null); }
    public String readAssetText(String relativePath, String charset) throws IOException { return readAssetText(relativePath, charset, null); }
    public String readAssetText(String relativePath, String charset, Object options) throws IOException {
        return assetManager.readAssetText(relativePath, charset(charset), optionMaxBytes(options));
    }

    public Object readAssetBytes(String relativePath) throws IOException { return readAssetBytes(relativePath, null); }
    public Object readAssetBytes(String relativePath, Object options) throws IOException {
        return JsApiValueNormalizer.toJs(assetManager.readAssetBytes(relativePath, optionMaxBytes(options)));
    }

    public String appAssetDir(Object context) throws IOException { return appAssetDir(context, null); }
    public String appAssetDir(Object context, Object options) throws IOException {
        Context ctx = TargetAppPathHelper.requireContext(context);
        return assetManager.appAssetDir(ctx, options).getAbsolutePath();
    }

    public Object copyAssetToApp(Object context, String assetRelativePath) throws IOException {
        return copyAssetToApp(context, assetRelativePath, null, null);
    }

    public Object copyAssetToApp(Object context, String assetRelativePath, Object options) throws IOException {
        return copyAssetToApp(context, assetRelativePath, null, options);
    }

    public Object copyAssetToApp(Object context, String assetRelativePath, String targetRelativePath) throws IOException {
        return copyAssetToApp(context, assetRelativePath, targetRelativePath, null);
    }

    public Object copyAssetToApp(Object context, String assetRelativePath, String targetRelativePath, Object options) throws IOException {
        Context ctx = TargetAppPathHelper.requireContext(context);
        return JsApiValueNormalizer.toJs(assetManager.copyAssetToApp(ctx, assetRelativePath, targetRelativePath, options));
    }

    public Object syncAssetsToApp(Object context) throws IOException { return syncAssetsToApp(context, null); }
    public Object syncAssetsToApp(Object context, Object options) throws IOException {
        Context ctx = TargetAppPathHelper.requireContext(context);
        return JsApiValueNormalizer.toJs(assetManager.syncAssetsToApp(ctx, options));
    }

    @NonNull
    static Charset charset(@Nullable String name) {
        String clean = name == null || name.trim().isEmpty() ? "UTF-8" : name.trim();
        return Charset.forName(clean);
    }

    static int optionMaxBytes(@Nullable Object options) {
        int value = optionInt(options, "maxBytes", FileCopyUtils.DEFAULT_MAX_READ_BYTES);
        return value <= 0 ? FileCopyUtils.DEFAULT_MAX_READ_BYTES : value;
    }

    static boolean optionBoolean(@Nullable Object options, @NonNull String key, boolean fallback) {
        Object value = optionValue(options, key);
        if (value == null || value == Undefined.instance || value == Scriptable.NOT_FOUND) return fallback;
        Object raw = unwrap(value);
        if (raw instanceof Boolean) return (Boolean) raw;
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return fallback;
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    static int optionInt(@Nullable Object options, @NonNull String key, int fallback) {
        Object value = optionValue(options, key);
        if (value == null || value == Undefined.instance || value == Scriptable.NOT_FOUND) return fallback;
        Object raw = unwrap(value);
        if (raw instanceof Number) return ((Number) raw).intValue();
        try { return Integer.parseInt(String.valueOf(raw).trim()); } catch (Throwable ignored) { return fallback; }
    }

    @NonNull
    static String optionString(@Nullable Object options, @NonNull String key, @NonNull String fallback) {
        Object value = optionValue(options, key);
        if (value == null || value == Undefined.instance || value == Scriptable.NOT_FOUND) return fallback;
        Object raw = unwrap(value);
        return raw == null ? fallback : String.valueOf(raw);
    }

    @Nullable
    static Object optionValue(@Nullable Object options, @NonNull String key) {
        Object raw = unwrap(options);
        if (raw == null || raw == Undefined.instance || raw == Scriptable.NOT_FOUND) return null;
        if (raw instanceof Scriptable) {
            Object value = ScriptableObject.getProperty((Scriptable) raw, key);
            return value == Scriptable.NOT_FOUND ? null : value;
        }
        if (raw instanceof Map<?, ?>) return ((Map<?, ?>) raw).get(key);
        if (raw instanceof JSONObject) return ((JSONObject) raw).opt(key);
        return null;
    }

    @Nullable
    static Object unwrap(@Nullable Object value) {
        Object current = value;
        for (int i = 0; i < 4; i++) {
            if (current instanceof Wrapper) {
                current = ((Wrapper) current).unwrap();
                continue;
            }
            return current;
        }
        return current;
    }

    @NonNull
    private static String requirePath(@Nullable String path) {
        String clean = path == null ? "" : path.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("path 不能为空");
        if (clean.indexOf('\0') >= 0) throw new IllegalArgumentException("path 包含 NUL 字符");
        return clean;
    }

    @NonNull
    private static byte[] toByteArray(@Nullable Object value) {
        Object raw = unwrap(value);
        if (raw == null || raw == Undefined.instance || raw == Scriptable.NOT_FOUND) {
            throw new IllegalArgumentException("bytes 不能为空");
        }
        if (raw instanceof byte[]) return (byte[]) raw;
        if (raw instanceof Byte[]) {
            Byte[] boxed = (Byte[]) raw;
            byte[] out = new byte[boxed.length];
            for (int i = 0; i < boxed.length; i++) out[i] = boxed[i] == null ? 0 : boxed[i];
            return out;
        }
        if (raw instanceof NativeArray || raw instanceof Scriptable) {
            Scriptable scriptable = (Scriptable) raw;
            long length = lengthOf(scriptable);
            if (length > Integer.MAX_VALUE) throw new IllegalArgumentException("bytes 数组过长: " + length);
            byte[] out = new byte[(int) length];
            for (int i = 0; i < out.length; i++) {
                Object item = ScriptableObject.getProperty(scriptable, i);
                out[i] = (byte) toByteInt(item);
            }
            return out;
        }
        Class<?> cls = raw.getClass();
        if (cls.isArray()) {
            int length = Array.getLength(raw);
            byte[] out = new byte[length];
            for (int i = 0; i < length; i++) out[i] = (byte) toByteInt(Array.get(raw, i));
            return out;
        }
        if (raw instanceof Iterable<?>) {
            ArrayList<Byte> list = new ArrayList<>();
            for (Object item : (Iterable<?>) raw) list.add((byte) toByteInt(item));
            byte[] out = new byte[list.size()];
            for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
            return out;
        }
        throw new IllegalArgumentException("不支持的 bytes 类型: " + raw.getClass().getName());
    }

    private static long lengthOf(@NonNull Scriptable scriptable) {
        Object value = ScriptableObject.getProperty(scriptable, "length");
        if (value == null || value == Undefined.instance || value == Scriptable.NOT_FOUND) return 0;
        Object raw = unwrap(value);
        if (raw instanceof Number) return ((Number) raw).longValue();
        try { return Long.parseLong(String.valueOf(raw)); } catch (Throwable ignored) { return 0; }
    }

    private static int toByteInt(@Nullable Object value) {
        Object raw = unwrap(value);
        if (raw instanceof Number) return ((Number) raw).intValue() & 0xff;
        try { return Integer.parseInt(String.valueOf(raw).trim()) & 0xff; } catch (Throwable ignored) { return 0; }
    }
}
