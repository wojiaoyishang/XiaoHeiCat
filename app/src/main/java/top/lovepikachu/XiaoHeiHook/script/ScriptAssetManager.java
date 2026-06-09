package top.lovepikachu.XiaoHeiHook.script;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import top.lovepikachu.XiaoHeiHook.HookEntry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads current script assets and copies them into target app private files. */
public final class ScriptAssetManager {
    private static final String APP_ASSET_FOLDER = "xhh_assets";

    private final HookEntry module;
    private final ScriptPathResolver resolver;
    private final Map<String, String> remoteAssets;
    private final Map<String, String> remoteAssetHashes;

    public ScriptAssetManager(
            @NonNull HookEntry module,
            @NonNull ScriptPathResolver resolver,
            @Nullable Map<String, String> remoteAssets,
            @Nullable Map<String, String> remoteAssetHashes
    ) {
        this.module = module;
        this.resolver = resolver;
        this.remoteAssets = normalizeAssetMap(remoteAssets);
        this.remoteAssetHashes = normalizeAssetMap(remoteAssetHashes);
    }

    @NonNull
    public File assetPath(@NonNull String relativePath, boolean requireExists) throws IOException {
        return resolver.resolveAssetPath(relativePath, requireExists);
    }

    @NonNull
    public byte[] readAssetBytes(@NonNull String relativePath, int maxBytes) throws IOException {
        String clean = ScriptPathResolver.requireSafeRelativePath(relativePath, "assetRelativePath");
        String remoteName = remoteAssets.get(clean);
        if (remoteName != null && !remoteName.trim().isEmpty()) {
            try {
                byte[] bytes = module.readRemoteBinaryFile(remoteName, remoteAssetHashes.get(clean), clean);
                if (maxBytes > 0 && bytes.length > maxBytes) {
                    throw new IOException("asset 超过读取限制: " + clean + ", size=" + bytes.length + ", max=" + maxBytes);
                }
                return bytes;
            } catch (IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new IOException("读取 Remote asset 失败: " + clean + " -> " + remoteName, t);
            }
        }
        return FileCopyUtils.readAll(assetPath(clean, true), maxBytes);
    }

    @NonNull
    public String readAssetText(@NonNull String relativePath, @NonNull Charset charset, int maxBytes) throws IOException {
        return new String(readAssetBytes(relativePath, maxBytes), charset);
    }

    @NonNull
    public File appAssetDir(@NonNull Context context, @Nullable Object options) throws IOException {
        String baseName = FileJsApi.optionString(options, "base", "files").trim();
        if (!baseName.isEmpty() && !"files".equals(baseName)) {
            throw new IOException("xhh.fs asset 复制第一版只支持目标 App filesDir，不支持 base=" + baseName);
        }
        File filesDir = context.getFilesDir();
        if (filesDir == null) throw new IOException("目标 App filesDir 不可用: " + context.getPackageName());
        File privateRoot = filesDir.getCanonicalFile();
        String customRoot = FileJsApi.optionString(options, "rootDir", "").trim();
        File targetBase;
        if (!customRoot.isEmpty()) {
            targetBase = resolveTargetRoot(privateRoot, customRoot);
        } else {
            boolean versioned = FileJsApi.optionBoolean(options, "versioned", true);
            targetBase = new File(new File(privateRoot, APP_ASSET_FOLDER), resolver.info().scriptId);
            if (versioned) targetBase = new File(targetBase, resolver.info().scriptVersion);
            targetBase = targetBase.getCanonicalFile();
        }
        ScriptPathResolver.requireInside(privateRoot, targetBase, "目标 asset 根目录必须位于 App filesDir 内");
        return targetBase;
    }

    @NonNull
    public LinkedHashMap<String, Object> copyAssetToApp(
            @NonNull Context context,
            @NonNull String assetRelativePath,
            @Nullable String targetRelativePath,
            @Nullable Object options
    ) throws IOException {
        String cleanAsset = ScriptPathResolver.requireSafeRelativePath(assetRelativePath, "assetRelativePath");
        byte[] bytes = readAssetBytes(cleanAsset, FileJsApi.optionMaxBytes(options));
        String sha256 = remoteAssetHashes.get(cleanAsset);
        File baseDir = appAssetDir(context, options);
        String targetPath = targetRelativePath == null || targetRelativePath.trim().isEmpty() ? cleanAsset : targetRelativePath;
        File dst = resolveTargetPath(context, baseDir, targetPath);
        boolean overwrite = FileJsApi.optionBoolean(options, "overwrite", true);
        boolean existed = dst.exists();
        long copiedBytes = FileCopyUtils.copy(bytes, dst, overwrite);
        boolean skipped = existed && !overwrite;

        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("asset", cleanAsset);
        out.put("path", dst.getAbsolutePath());
        out.put("baseDir", baseDir.getAbsolutePath());
        out.put("bytes", skipped ? dst.length() : copiedBytes);
        out.put("sha256", sha256 == null ? FileCopyUtils.sha256(bytes) : sha256);
        out.put("copied", !skipped);
        out.put("skipped", skipped);
        out.put("overwritten", existed && !skipped);
        return out;
    }

    @NonNull
    public LinkedHashMap<String, Object> syncAssetsToApp(@NonNull Context context, @Nullable Object options) throws IOException {
        File sourceDir = resolver.assetsDir();
        File targetDir = appAssetDir(context, options);
        FileCopyUtils.mkdirs(targetDir);
        String overwriteMode = FileJsApi.optionString(options, "overwrite", "true").toLowerCase(Locale.ROOT);
        boolean clean = FileJsApi.optionBoolean(options, "clean", false);
        int maxBytes = FileJsApi.optionMaxBytes(options);

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        ArrayList<Object> files = new ArrayList<>();
        int copied = 0;
        int skipped = 0;
        int overwritten = 0;

        Set<String> assets = availableAssets();
        for (String asset : assets) {
            byte[] bytes = readAssetBytes(asset, maxBytes);
            File dst = resolveTargetPath(context, targetDir, asset);
            boolean existed = dst.exists();
            String sha256 = remoteAssetHashes.get(asset);
            boolean shouldCopy;
            if ("false".equals(overwriteMode)) {
                shouldCopy = !existed;
            } else if ("changed".equals(overwriteMode)) {
                shouldCopy = FileCopyUtils.isChanged(dst, bytes.length, sha256 == null ? FileCopyUtils.sha256(bytes) : sha256);
            } else {
                shouldCopy = true;
            }

            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("asset", asset);
            item.put("path", dst.getAbsolutePath());
            item.put("bytes", bytes.length);
            item.put("sha256", sha256 == null ? FileCopyUtils.sha256(bytes) : sha256);

            if (shouldCopy) {
                FileCopyUtils.copy(bytes, dst, true);
                copied++;
                if (existed) overwritten++;
                item.put("copied", true);
                item.put("skipped", false);
                item.put("overwritten", existed);
            } else {
                skipped++;
                item.put("copied", false);
                item.put("skipped", true);
                item.put("overwritten", false);
            }
            files.add(item);
        }

        int deleted = 0;
        if (clean) {
            deleted = cleanMissing(targetDir, assets);
        }

        result.put("sourceDir", sourceDir.getAbsolutePath());
        result.put("targetDir", targetDir.getAbsolutePath());
        result.put("copied", copied);
        result.put("skipped", skipped);
        result.put("overwritten", overwritten);
        result.put("deleted", deleted);
        result.put("files", files);
        return result;
    }

    @NonNull
    private Set<String> availableAssets() throws IOException {
        LinkedHashSet<String> out = new LinkedHashSet<>(remoteAssets.keySet());
        File root = resolver.assetsDir();
        if (root.isDirectory()) {
            collectLocalAssets(root.getCanonicalFile(), root, out);
        }
        ArrayList<String> sorted = new ArrayList<>(out);
        Collections.sort(sorted);
        return new LinkedHashSet<>(sorted);
    }

    private void collectLocalAssets(@NonNull File canonicalRoot, @NonNull File dir, @NonNull Set<String> out) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            File canonical = child.getCanonicalFile();
            ScriptPathResolver.requireInside(canonicalRoot, canonical, "asset 符号链接越界");
            if (canonical.isDirectory()) {
                collectLocalAssets(canonicalRoot, canonical, out);
            } else if (canonical.isFile()) {
                String rootPath = canonicalRoot.getPath();
                String childPath = canonical.getPath();
                String rel = childPath.equals(rootPath) ? canonical.getName() : childPath.substring(rootPath.length() + 1);
                out.add(rel.replace(File.separatorChar, '/'));
            }
        }
    }

    private int cleanMissing(@NonNull File targetDir, @NonNull Set<String> keepAssets) throws IOException {
        File canonicalRoot = targetDir.getCanonicalFile();
        if (!canonicalRoot.exists()) return 0;
        ArrayList<File> all = new ArrayList<>();
        collectFiles(canonicalRoot, canonicalRoot, all);
        int deleted = 0;
        for (File file : all) {
            String rootPath = canonicalRoot.getPath();
            String filePath = file.getCanonicalPath();
            String rel = filePath.equals(rootPath) ? file.getName() : filePath.substring(rootPath.length() + 1);
            rel = rel.replace(File.separatorChar, '/');
            if (!keepAssets.contains(rel)) {
                FileCopyUtils.deleteRecursive(file);
                deleted++;
            }
        }
        pruneEmptyDirs(canonicalRoot, canonicalRoot);
        return deleted;
    }

    private void collectFiles(@NonNull File root, @NonNull File dir, @NonNull ArrayList<File> out) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            File canonical = child.getCanonicalFile();
            ScriptPathResolver.requireInside(root, canonical, "清理目录越界");
            if (canonical.isDirectory()) collectFiles(root, canonical, out);
            else if (canonical.isFile()) out.add(canonical);
        }
    }

    private boolean pruneEmptyDirs(@NonNull File root, @NonNull File dir) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return false;
        boolean empty = true;
        for (File child : children) {
            File canonical = child.getCanonicalFile();
            ScriptPathResolver.requireInside(root, canonical, "清理目录越界");
            if (canonical.isDirectory()) {
                if (!pruneEmptyDirs(root, canonical)) empty = false;
            } else if (canonical.exists()) {
                empty = false;
            }
        }
        if (empty && !dir.getCanonicalPath().equals(root.getCanonicalPath())) {
            if (!dir.delete() && dir.exists()) throw new IOException("删除空目录失败: " + dir.getAbsolutePath());
            return true;
        }
        return false;
    }

    @NonNull
    private File resolveTargetPath(@NonNull Context context, @NonNull File baseDir, @NonNull String targetPath) throws IOException {
        File filesDir = context.getFilesDir();
        if (filesDir == null) throw new IOException("目标 App filesDir 不可用: " + context.getPackageName());
        File privateRoot = filesDir.getCanonicalFile();
        String raw = targetPath.replace('\\', '/').trim();
        if (raw.indexOf('\0') >= 0) throw new IOException("目标路径包含 NUL 字符");
        File dst;
        if (new File(raw).isAbsolute() || raw.startsWith("/")) {
            dst = new File(raw).getCanonicalFile();
        } else {
            String clean = ScriptPathResolver.requireSafeRelativePath(raw, "targetRelativePath");
            dst = new File(baseDir, clean).getCanonicalFile();
        }
        ScriptPathResolver.requireInside(privateRoot, dst, "目标路径必须位于 App filesDir 内");
        ScriptPathResolver.requireInside(baseDir, dst, "目标路径必须位于本次 asset 根目录内");
        return dst;
    }

    @NonNull
    private File resolveTargetRoot(@NonNull File privateRoot, @NonNull String rootDir) throws IOException {
        String raw = rootDir.replace('\\', '/').trim();
        if (raw.indexOf('\0') >= 0) throw new IOException("rootDir 包含 NUL 字符");
        File root;
        if (new File(raw).isAbsolute() || raw.startsWith("/")) {
            root = new File(raw).getCanonicalFile();
        } else {
            root = new File(privateRoot, ScriptPathResolver.requireSafeRelativePath(raw, "rootDir")).getCanonicalFile();
        }
        ScriptPathResolver.requireInside(privateRoot, root, "rootDir 必须位于 App filesDir 内");
        return root;
    }

    @NonNull
    private Map<String, String> normalizeAssetMap(@Nullable Map<String, String> input) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (input == null) return out;
        for (Map.Entry<String, String> entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = resolver.assetRelativeFromScriptPath(entry.getKey());
            if (!key.isEmpty()) out.put(key, entry.getValue());
        }
        return out;
    }
}
