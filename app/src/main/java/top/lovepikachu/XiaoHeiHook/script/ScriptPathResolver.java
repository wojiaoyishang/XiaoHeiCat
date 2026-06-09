package top.lovepikachu.XiaoHeiHook.script;

import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Resolves current script root, script directory and assets directory for xhh.fs. */
public final class ScriptPathResolver {
    public static final String DEFAULT_FOLDER_NAME = "XiaoHeiHook";

    public static final class ScriptContextInfo {
        public final String scriptId;
        public final String scriptName;
        public final String scriptVersion;
        public final String scriptSourcePath;
        public final String scriptRootPath;
        public final String scriptRootRelativePath;

        public ScriptContextInfo(
                @Nullable String scriptId,
                @Nullable String scriptName,
                @Nullable String scriptVersion,
                @Nullable String scriptSourcePath,
                @Nullable String scriptRootPath,
                @Nullable String scriptRootRelativePath
        ) {
            this.scriptId = cleanId(scriptId);
            this.scriptName = clean(scriptName);
            this.scriptVersion = clean(scriptVersion).isEmpty() ? "1.0.0" : clean(scriptVersion);
            this.scriptSourcePath = normalizeRelativePath(clean(scriptSourcePath));
            this.scriptRootPath = clean(scriptRootPath).isEmpty() ? defaultScriptRoot().getAbsolutePath() : clean(scriptRootPath);
            this.scriptRootRelativePath = normalizeRelativePath(clean(scriptRootRelativePath));
        }
    }

    private final ScriptContextInfo info;

    public ScriptPathResolver(@NonNull ScriptContextInfo info) {
        this.info = info;
    }

    @NonNull
    public ScriptContextInfo info() {
        return info;
    }

    @NonNull
    public File scriptRoot() {
        return new File(info.scriptRootPath).getAbsoluteFile();
    }

    @NonNull
    public File scriptFile() {
        String source = info.scriptSourcePath;
        return source.isEmpty() ? scriptRoot() : new File(scriptRoot(), source).getAbsoluteFile();
    }

    @NonNull
    public File scriptDir() {
        String source = info.scriptSourcePath;
        if (source.isEmpty()) return scriptRoot();
        File sourceFile = new File(scriptRoot(), source);
        File parent = sourceFile.getParentFile();
        return parent == null ? scriptRoot() : parent.getAbsoluteFile();
    }

    @NonNull
    public File assetsDir() {
        return new File(scriptDir(), "assets").getAbsoluteFile();
    }

    @NonNull
    public File resolveAssetPath(@NonNull String relativePath, boolean requireExists) throws IOException {
        String clean = requireSafeRelativePath(relativePath, "assetRelativePath");
        File root = assetsDir().getCanonicalFile();
        File target = new File(root, clean).getCanonicalFile();
        requireInside(root, target, "asset 路径越界");
        if (requireExists && !target.isFile()) {
            throw new java.io.FileNotFoundException("asset 不存在或不是文件: " + clean + " -> " + target.getAbsolutePath());
        }
        return target;
    }

    @NonNull
    public String assetRelativeFromScriptPath(@Nullable String path) {
        String clean = normalizeRelativePath(clean(path));
        if (clean.isEmpty()) return "";
        String prefix;
        if (!info.scriptRootRelativePath.isEmpty()) {
            prefix = info.scriptRootRelativePath + "/assets/";
            if (clean.startsWith(prefix)) return normalizeRelativePath(clean.substring(prefix.length()));
        }
        if (clean.startsWith("assets/")) return normalizeRelativePath(clean.substring("assets/".length()));
        int assetsIdx = clean.indexOf("/assets/");
        if (assetsIdx >= 0) return normalizeRelativePath(clean.substring(assetsIdx + "/assets/".length()));
        return clean;
    }

    @NonNull
    public static File defaultScriptRoot() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DEFAULT_FOLDER_NAME);
    }

    @NonNull
    public static String normalizeRelativePath(@Nullable String value) {
        String path = clean(value).replace('\\', '/').trim();
        while (path.startsWith("/")) path = path.substring(1);
        while (path.contains("//")) path = path.replace("//", "/");
        if (path.isEmpty()) return "";
        String[] parts = path.split("/");
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        for (String raw : parts) {
            String part = raw == null ? "" : raw.trim();
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (stack.isEmpty()) throw new IllegalArgumentException("路径越界: " + value);
                stack.removeLast();
            } else {
                stack.addLast(part);
            }
        }
        return String.join("/", stack);
    }

    @NonNull
    public static String requireSafeRelativePath(@Nullable String value, @NonNull String label) {
        String raw = clean(value).replace('\\', '/').trim();
        if (raw.isEmpty()) throw new IllegalArgumentException(label + " 不能为空");
        if (raw.indexOf('\0') >= 0) throw new IllegalArgumentException(label + " 包含 NUL 字符");
        if (new File(raw).isAbsolute() || raw.startsWith("/")) throw new IllegalArgumentException(label + " 不能是绝对路径: " + raw);
        String clean = normalizeRelativePath(raw);
        if (clean.isEmpty()) throw new IllegalArgumentException(label + " 不能为空");
        return clean;
    }

    public static void requireInside(@NonNull File root, @NonNull File target, @NonNull String message) throws IOException {
        File safeRoot = root.getCanonicalFile();
        File safeTarget = target.getCanonicalFile();
        String rootPath = safeRoot.getPath();
        String targetPath = safeTarget.getPath();
        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            throw new IOException(message + ": " + targetPath + " !< " + rootPath);
        }
    }

    @NonNull
    private static String cleanId(@Nullable String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return "unnamed";
        return clean.replaceAll("[^A-Za-z0-9_.-]", "_").replaceAll("_+", "_").toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
