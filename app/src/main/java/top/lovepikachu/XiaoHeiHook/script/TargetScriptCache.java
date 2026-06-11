package top.lovepikachu.XiaoHeiHook.script;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public final class TargetScriptCache {
    private static final String TAG = "XiaoHeiHook-TargetCache";
    public static final String DEFAULT_ROOT_DIR = ".xhh_scripts";
    private static final String SCRIPTS_DIR = "scripts";
    private static final String INDEX_FILE = "index.json";

    private TargetScriptCache() {}

    public static String readIfHashMatch(Context context, String scriptId, String sha256) {
        return readIfHashMatch(context, DEFAULT_ROOT_DIR, scriptId, sha256);
    }

    public static String readIfHashMatch(Context context, String rootDirName, String scriptId, String sha256) {
        if (context == null) return null;
        return readIfHashMatch(context.getFilesDir(), context.getPackageName(), rootDirName, scriptId, sha256);
    }

    public static String readIfHashMatch(File filesDir, String packageName, String rootDirName, String scriptId, String sha256) {
        if (filesDir == null || isBlank(packageName) || isBlank(scriptId) || isBlank(sha256)) return null;
        File file = scriptFile(filesDir, rootDirName, scriptId, sha256);
        if (!file.isFile()) return null;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            String actual = sha256(bytes);
            if (!sha256.equalsIgnoreCase(actual)) {
                // Corrupted cache must not be reused.
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                Log.w(TAG, "target private cache hash mismatch, delete: scriptId=" + scriptId + " expected=" + sha256 + " actual=" + actual);
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "read target private script cache failed: package=" + packageName + ", scriptId=" + scriptId + ", dir=" + normalizeRootDir(rootDirName), t);
            return null;
        }
    }

    public static void write(Context context, String scriptId, String sha256, String source) {
        write(context, DEFAULT_ROOT_DIR, scriptId, sha256, source);
    }

    public static void write(Context context, String rootDirName, String scriptId, String sha256, String source) {
        if (context == null) return;
        write(context.getFilesDir(), context.getPackageName(), rootDirName, scriptId, sha256, source);
    }

    public static void write(File filesDir, String packageName, String rootDirName, String scriptId, String sha256, String source) {
        if (filesDir == null || isBlank(packageName) || isBlank(scriptId) || isBlank(sha256) || source == null) return;
        try {
            File file = scriptFile(filesDir, rootDirName, scriptId, sha256);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
            String actual = sha256(bytes);
            if (!sha256.equalsIgnoreCase(actual)) {
                Log.w(TAG, "skip writing target private cache, hash mismatch: scriptId=" + scriptId + " expected=" + sha256 + " actual=" + actual);
                return;
            }
            java.nio.file.Files.write(file.toPath(), bytes);
            File root = root(filesDir, rootDirName);
            updateIndex(filesDir, packageName, rootDirName, scriptId, sha256, root.toPath().relativize(file.toPath()).toString().replace('\\', '/'));
        } catch (Throwable t) {
            Log.w(TAG, "write target private script cache failed: package=" + packageName + ", scriptId=" + scriptId + ", dir=" + normalizeRootDir(rootDirName), t);
        }
    }

    public static void prune(Context context, Set<String> activeScriptIds) {
        prune(context, DEFAULT_ROOT_DIR, activeScriptIds);
    }

    public static void prune(Context context, String rootDirName, Set<String> activeScriptIds) {
        if (context == null || activeScriptIds == null) return;
        try {
            File dir = new File(root(context, rootDirName), SCRIPTS_DIR);
            File[] files = dir.listFiles();
            if (files == null) return;
            Set<String> safeActive = new HashSet<>();
            for (String id : activeScriptIds) safeActive.add(sanitize(id));
            for (File file : files) {
                String name = file.getName();
                int idx = name.lastIndexOf('_');
                String prefix = idx > 0 ? name.substring(0, idx) : name;
                if (!safeActive.contains(prefix)) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "prune target private script cache failed, dir=" + normalizeRootDir(rootDirName), t);
        }
    }


    public static boolean deleteRoot(File filesDir, String rootDirName) {
        if (filesDir == null) return false;
        try {
            File base = filesDir.getCanonicalFile();
            File dir = root(filesDir, rootDirName).getCanonicalFile();
            String basePath = base.getPath();
            String dirPath = dir.getPath();
            if (dirPath.equals(basePath) || !dirPath.startsWith(basePath + File.separator)) {
                Log.w(TAG, "skip deleting target private cache outside filesDir: dir=" + dirPath + ", filesDir=" + basePath);
                return false;
            }
            if (!dir.exists()) return true;
            return deleteRecursively(dir);
        } catch (Throwable t) {
            Log.w(TAG, "delete target private script cache failed, dir=" + normalizeRootDir(rootDirName), t);
            return false;
        }
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        return file.delete() || !file.exists();
    }

    public static String normalizeRootDir(String value) {
        String clean = value == null ? "" : value.trim().replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        while (clean.contains("//")) clean = clean.replace("//", "/");
        while (clean.startsWith("/")) clean = clean.substring(1);
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        if (clean.trim().isEmpty()) return DEFAULT_ROOT_DIR;
        if (DEFAULT_ROOT_DIR.equals(clean)) return DEFAULT_ROOT_DIR;
        String[] rawParts = clean.split("/");
        if (rawParts.length == 0) return DEFAULT_ROOT_DIR;
        StringBuilder normalized = new StringBuilder();
        for (String rawPart : rawParts) {
            if (rawPart == null || rawPart.isEmpty() || ".".equals(rawPart) || "..".equals(rawPart)) return DEFAULT_ROOT_DIR;
            String part = rawPart;
            while (part.startsWith(".")) part = part.substring(1);
            if (part.isEmpty()) return DEFAULT_ROOT_DIR;
            if (!part.matches("[A-Za-z0-9._-]{1,80}")) return DEFAULT_ROOT_DIR;
            if (normalized.length() > 0) normalized.append('/');
            normalized.append(part);
        }
        return normalized.toString();
    }

    private static void updateIndex(File filesDir, String packageName, String rootDirName, String scriptId, String sha256, String relativePath) throws Exception {
        File index = new File(root(filesDir, rootDirName), INDEX_FILE);
        JSONObject root = index.isFile()
                ? new JSONObject(new String(java.nio.file.Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8))
                : new JSONObject();
        root.put("version", 1);
        root.put("packageName", packageName);
        root.put("rootDir", normalizeRootDir(rootDirName));
        JSONArray scripts = root.optJSONArray("scripts");
        if (scripts == null) scripts = new JSONArray();

        JSONArray next = new JSONArray();
        for (int i = 0; i < scripts.length(); i++) {
            JSONObject obj = scripts.optJSONObject(i);
            if (obj == null) continue;
            if (!scriptId.equals(obj.optString("scriptId"))) next.put(obj);
        }
        next.put(new JSONObject()
                .put("scriptId", scriptId)
                .put("sha256", sha256)
                .put("path", relativePath)
                .put("updatedAt", System.currentTimeMillis()));
        root.put("scripts", next);
        File parent = index.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        java.nio.file.Files.write(index.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static File root(Context context, String rootDirName) {
        return context == null ? null : root(context.getFilesDir(), rootDirName);
    }

    private static File root(File filesDir, String rootDirName) {
        File base = filesDir;
        if (base == null) return new File(DEFAULT_ROOT_DIR);
        File dir = new File(base, normalizeRootDir(rootDirName));
        try {
            File canonicalBase = base.getCanonicalFile();
            File canonicalDir = dir.getCanonicalFile();
            String basePath = canonicalBase.getPath();
            String dirPath = canonicalDir.getPath();
            if (dirPath.equals(basePath) || !dirPath.startsWith(basePath + File.separator)) {
                return new File(base, DEFAULT_ROOT_DIR);
            }
        } catch (Throwable ignored) {
            return new File(base, DEFAULT_ROOT_DIR);
        }
        return dir;
    }

    private static File scriptFile(Context context, String rootDirName, String scriptId, String sha256) {
        return scriptFile(context.getFilesDir(), rootDirName, scriptId, sha256);
    }

    private static File scriptFile(File filesDir, String rootDirName, String scriptId, String sha256) {
        String cleanSha = sha256.replaceAll("[^A-Fa-f0-9]", "").toLowerCase();
        return new File(new File(root(filesDir, rootDirName), SCRIPTS_DIR), sanitize(scriptId) + "_" + cleanSha + ".js");
    }

    private static String sanitize(String value) {
        if (value == null) return "unnamed";
        String clean = value.replaceAll("[^A-Za-z0-9._-]", "_");
        while (clean.startsWith("_")) clean = clean.substring(1);
        while (clean.endsWith("_")) clean = clean.substring(0, clean.length() - 1);
        return clean.isEmpty() ? "unnamed" : clean;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value == null ? new byte[0] : value);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
