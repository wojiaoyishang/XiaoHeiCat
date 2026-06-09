package top.lovepikachu.XiaoHeiHook;

import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import android.os.Environment;
import java.io.File;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import io.github.libxposed.api.XposedModule;
import top.lovepikachu.XiaoHeiHook.script.JsHookRuntime;
import top.lovepikachu.XiaoHeiHook.dex.DexRuntimeRegistry;
import top.lovepikachu.XiaoHeiHook.dex.DexClassLoadDumper;

public class HookEntry extends XposedModule {
    private static final String TAG = "XiaoHeiHook-Entry";
    private static final String PREF_GROUP = "XiaoHeiHookSetting";
    private static final String SCRIPT_INDEX_JSON = "script_index_json";

    private SharedPreferences prefs;
    private String processName = "unknown";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        processName = param.getProcessName();
        prefs = getRemotePreferences(PREF_GROUP);
        log(Log.INFO, TAG, "模块已加载: " + processName + ", systemServer=" + param.isSystemServer());
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.Q)
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        installDexRuntimeCaptureEarly(param.getPackageName(), param.getDefaultClassLoader(), "package-loaded");
        dispatchScripts(
                param.getPackageName(),
                param.getDefaultClassLoader(),
                JsHookRuntime.EVENT_PACKAGE_LOADED,
                param
        );
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.Q)
    public void onPackageReady(@NonNull PackageReadyParam param) {
        ClassLoader loader;
        try {
            loader = param.getClassLoader();
        } catch (Throwable ignored) {
            loader = param.getDefaultClassLoader();
        }
        installDexRuntimeCaptureEarly(param.getPackageName(), loader, "package-ready");
        dispatchScripts(
                param.getPackageName(),
                loader,
                JsHookRuntime.EVENT_PACKAGE_READY,
                param
        );
    }


    private void installDexRuntimeCaptureEarly(@NonNull String packageName, @NonNull ClassLoader classLoader, @NonNull String stage) {
        if (!hasEnabledDexDumpGrant(packageName)) {
            log(Log.DEBUG, TAG, "Dex dump/runtime capture not enabled: no enabled script declares @grant dex.dump/dumpdex/dex.full for " + packageName + " @" + stage);
            return;
        }
        try {
            DexRuntimeRegistry.setDebugLogging(hasEnabledDexDebugGrant(packageName));
            DexRuntimeRegistry.install(this, packageName, processName, classLoader);
            log(Log.INFO, TAG, "Dex runtime capture early install ok: " + packageName + " @" + stage);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Dex runtime capture early install failed: " + packageName + " @" + stage, t);
        }
        try {
            DexClassLoadDumper.install(this, packageName, processName, classLoader, hasEnabledDexDebugGrant(packageName));
            log(Log.INFO, TAG, "Dex class-load dumper early install ok: " + packageName + " @" + stage);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Dex class-load dumper early install failed: " + packageName + " @" + stage, t);
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private void dispatchScripts(
            @NonNull String packageName,
            @NonNull ClassLoader classLoader,
            @NonNull String runAt,
            @NonNull Object rawParam
    ) {
        if (prefs == null) return;

        if (!prefs.getBoolean(appEnabledKey(packageName), false)) return;

        List<ScriptDescriptor> scripts = parseScriptIndex(prefs.getString(SCRIPT_INDEX_JSON, "[]"));
        if (scripts.isEmpty()) {
            log(Log.INFO, TAG, "未找到可用脚本: " + packageName);
            return;
        }

        for (ScriptDescriptor script : scripts) {
            if (!runAt.equals(script.runAt)) continue;
            if (!script.supportsPackage(packageName)) continue;
            if (!script.supportsProcess(processName)) continue;
            if (!prefs.getBoolean(scriptEnabledKey(packageName, script.id), false)) continue;

            try {
                String source = JsHookRuntime.readRemoteText(openRemoteFile(script.remoteName));
                verifyRemoteText(script.sourceName(), script.remoteName, source, script.fileHashes.get(script.sourceName()));
                JsHookRuntime runtime = new JsHookRuntime(
                        this,
                        packageName,
                        processName,
                        classLoader,
                        runAt,
                        rawParam,
                        script.rawEnabled(),
                        script.files,
                        script.fileHashes,
                        mergedSettingsJson(packageName, script),
                        script.grants,
                        script.id,
                        script.version,
                        scriptRootPath(),
                        script.rootPath,
                        script.assetFiles,
                        script.assetFileHashes
                );
                runtime.evaluate(script.displayName(), script.sourceName(), source);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "加载脚本失败: " + script.id + " -> " + packageName + " @" + runAt, t);
            }
        }
    }

    private boolean hasEnabledDexDumpGrant(@NonNull String packageName) {
        try {
            if (prefs == null || !prefs.getBoolean(appEnabledKey(packageName), false)) return false;
            List<ScriptDescriptor> scripts = parseScriptIndex(prefs.getString(SCRIPT_INDEX_JSON, "[]"));
            for (ScriptDescriptor script : scripts) {
                if (!script.supportsPackage(packageName)) continue;
                if (!script.supportsProcess(processName)) continue;
                if (!prefs.getBoolean(scriptEnabledKey(packageName, script.id), false)) continue;
                if (script.dexDumpEnabled()) return true;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "检查 dex.dump grant 失败: " + packageName, t);
        }
        return false;
    }

    private boolean hasEnabledDexDebugGrant(@NonNull String packageName) {
        try {
            if (prefs == null || !prefs.getBoolean(appEnabledKey(packageName), false)) return false;
            List<ScriptDescriptor> scripts = parseScriptIndex(prefs.getString(SCRIPT_INDEX_JSON, "[]"));
            for (ScriptDescriptor script : scripts) {
                if (!script.supportsPackage(packageName)) continue;
                if (!script.supportsProcess(processName)) continue;
                if (!prefs.getBoolean(scriptEnabledKey(packageName, script.id), false)) continue;
                if (script.dexDebugEnabled()) return true;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "检查 dex.debug grant 失败: " + packageName, t);
        }
        return false;
    }

    private void verifyRemoteText(String path, String remoteName, String source, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.trim().isEmpty()) return;
        String actual = sha256(source == null ? "" : source);
        if (!expectedSha256.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("Remote File SHA-256 校验失败: " + path + " -> " + remoteName + ", expected=" + expectedSha256 + ", actual=" + actual);
        }
    }

    private static String sha256(String value) {
        return sha256(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value == null ? new byte[0] : value);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Throwable t) {
            throw new IllegalStateException("SHA-256 计算失败", t);
        }
    }

    private static String appEnabledKey(String packageName) {
        return "app_enabled_" + packageName;
    }

    private static String scriptEnabledKey(String packageName, String scriptId) {
        return "script_enabled_" + packageName + "_" + scriptId;
    }

    private static String scriptSettingsKey(String packageName, String scriptId) {
        return "script_settings_" + packageName + "_" + scriptId;
    }

    private String scriptRootPath() {
        String configured = prefs == null ? null : prefs.getString("scriptRoot", null);
        if (configured == null || configured.trim().isEmpty()) {
            configured = prefs == null ? null : prefs.getString("script.root", null);
        }
        if (configured == null || configured.trim().isEmpty()) {
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "XiaoHeiHook").getAbsolutePath();
        }
        return configured.trim();
    }

    private String mergedSettingsJson(String packageName, ScriptDescriptor script) {
        if (!script.hasSettings || script.settingsSchema == null) return "{}";
        try {
            JSONObject values = defaultsForFields(script.settingsSchema.optJSONArray("fields"));
            String raw = prefs == null ? null : prefs.getString(scriptSettingsKey(packageName, script.id), "{}");
            JSONObject saved = raw == null || raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
            JSONObject savedValues = saved.optJSONObject("values");
            if (savedValues == null) savedValues = saved;
            mergeKnownValues(values, savedValues, script.settingsSchema.optJSONArray("fields"));
            return values.toString();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "合并脚本设置失败: " + script.id + " -> " + packageName, t);
            return "{}";
        }
    }

    private static JSONObject defaultsForFields(JSONArray fields) {
        JSONObject out = new JSONObject();
        if (fields == null) return out;
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field == null) continue;
            String type = field.optString("type", "");
            if ("group".equals(type)) {
                copyInto(out, defaultsForFields(field.optJSONArray("items")));
                continue;
            }
            String key = field.optString("key", "").trim();
            if (key.isEmpty()) continue;
            try {
                if (field.has("default")) {
                    out.put(key, field.opt("default"));
                } else if ("switch".equals(type) || "checkbox".equals(type)) {
                    out.put(key, false);
                } else if ("number".equals(type)) {
                    out.put(key, field.optBoolean("integer", false) ? 0 : 0.0d);
                } else if ("text".equals(type)) {
                    out.put(key, "");
                } else if ("select".equals(type)) {
                    JSONArray options = field.optJSONArray("options");
                    JSONObject first = options == null ? null : options.optJSONObject(0);
                    out.put(key, first == null ? "" : first.opt("value"));
                } else if ("radio".equals(type)) {
                    out.put(key, field.has("value") ? field.opt("value") : false);
                } else if ("tags".equals(type) || "list".equals(type)) {
                    out.put(key, new JSONArray());
                } else if ("custom".equals(type)) {
                    out.put(key, new JSONObject());
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static void mergeKnownValues(JSONObject target, JSONObject savedValues, JSONArray fields) {
        if (savedValues == null || fields == null) return;
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field == null) continue;
            if ("group".equals(field.optString("type", ""))) {
                mergeKnownValues(target, savedValues, field.optJSONArray("items"));
                continue;
            }
            String key = field.optString("key", "").trim();
            if (!key.isEmpty() && savedValues.has(key)) {
                try { target.put(key, savedValues.opt(key)); } catch (Throwable ignored) {}
            }
        }
    }

    private static void copyInto(JSONObject target, JSONObject source) {
        if (target == null || source == null) return;
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try { target.put(key, source.opt(key)); } catch (Throwable ignored) {}
        }
    }

    private List<ScriptDescriptor> parseScriptIndex(String json) {
        ArrayList<ScriptDescriptor> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String id = obj.optString("id");
                String remoteName = obj.optString("remoteName");
                if (id.isEmpty() || remoteName.isEmpty()) continue;
                result.add(new ScriptDescriptor(
                        id,
                        obj.optString("name", id),
                        toStringList(obj.optJSONArray("targets")),
                        toStringList(obj.optJSONArray("processes")),
                        obj.optString("runAt", JsHookRuntime.EVENT_PACKAGE_LOADED),
                        toStringList(obj.optJSONArray("grants")),
                        remoteName,
                        obj.optString("version", "1.0.0"),
                        obj.optString("path", obj.optString("scriptPath", "")),
                        obj.optString("rootPath", ""),
                        parseFiles(obj.optJSONArray("files"), obj.optString("path", obj.optString("scriptPath", "")), remoteName),
                        parseFileHashes(obj.optJSONArray("files"), obj.optString("path", obj.optString("scriptPath", "")), obj.optString("sha256", "")),
                        parseAssets(obj.optJSONArray("assets"), obj.optString("rootPath", "")),
                        parseAssetHashes(obj.optJSONArray("assets"), obj.optString("rootPath", "")),
                        obj.optBoolean("hasSettings", false),
                        obj.optString("settingsPath", ""),
                        obj.optJSONObject("settingsSchema")
                ));
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "解析脚本索引失败", t);
        }
        return result;
    }

    private static List<String> toStringList(JSONArray array) {
        ArrayList<String> list = new ArrayList<>();
        if (array == null) return list;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) list.add(value);
        }
        return list;
    }

    private static Map<String, String> parseFiles(JSONArray array, String entryPath, String entryRemoteName) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (entryPath != null && !entryPath.trim().isEmpty() && entryRemoteName != null && !entryRemoteName.trim().isEmpty()) {
            map.put(normalizeScriptPath(entryPath), entryRemoteName.trim());
        }
        if (array == null) return map;
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String path = normalizeScriptPath(obj.optString("path", ""));
            String remoteName = obj.optString("remoteName", "").trim();
            if (!path.isEmpty() && !remoteName.isEmpty()) {
                map.put(path, remoteName);
            }
        }
        return map;
    }

    private static Map<String, String> parseFileHashes(JSONArray array, String entryPath, String entrySha256) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        String cleanEntry = normalizeScriptPath(entryPath);
        if (!cleanEntry.isEmpty() && entrySha256 != null && !entrySha256.trim().isEmpty()) {
            map.put(cleanEntry, entrySha256.trim());
        }
        if (array == null) return map;
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String path = normalizeScriptPath(obj.optString("path", ""));
            String sha256 = obj.optString("sha256", "").trim();
            if (!path.isEmpty() && !sha256.isEmpty()) {
                map.put(path, sha256);
            }
        }
        return map;
    }

    private static Map<String, String> parseAssets(JSONArray array, String rootPath) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (array == null) return map;
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String path = normalizeAssetRelativePath(obj.optString("path", ""), rootPath);
            String remoteName = obj.optString("remoteName", "").trim();
            if (!path.isEmpty() && !remoteName.isEmpty()) map.put(path, remoteName);
        }
        return map;
    }

    private static Map<String, String> parseAssetHashes(JSONArray array, String rootPath) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (array == null) return map;
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String path = normalizeAssetRelativePath(obj.optString("path", ""), rootPath);
            String sha256 = obj.optString("sha256", "").trim();
            if (!path.isEmpty() && !sha256.isEmpty()) map.put(path, sha256);
        }
        return map;
    }

    private static String normalizeAssetRelativePath(String path, String rootPath) {
        String clean = normalizeScriptPath(path);
        String root = normalizeScriptPath(rootPath);
        if (!root.isEmpty() && clean.startsWith(root + "/assets/")) {
            clean = clean.substring((root + "/assets/").length());
        } else if (clean.startsWith("assets/")) {
            clean = clean.substring("assets/".length());
        } else {
            int idx = clean.indexOf("/assets/");
            if (idx >= 0) clean = clean.substring(idx + "/assets/".length());
        }
        return normalizeScriptPath(clean);
    }

    private static String normalizeScriptPath(String path) {
        if (path == null) return "";
        String value = path.replace('\\', '/').trim();
        while (value.startsWith("/")) value = value.substring(1);
        while (value.contains("//")) value = value.replace("//", "/");
        return value;
    }

    public String readRemoteTextFile(@NonNull String remoteName) throws Exception {
        return JsHookRuntime.readRemoteText(openRemoteFile(remoteName));
    }

    public String readRemoteTextFile(@NonNull String remoteName, String expectedSha256, String path) throws Exception {
        String source = JsHookRuntime.readRemoteText(openRemoteFile(remoteName));
        verifyRemoteText(path == null ? remoteName : path, remoteName, source, expectedSha256);
        return source;
    }

    public byte[] readRemoteBinaryFile(@NonNull String remoteName, String expectedSha256, String path) throws Exception {
        byte[] bytes = JsHookRuntime.readRemoteBytes(openRemoteFile(remoteName));
        verifyRemoteBytes(path == null ? remoteName : path, remoteName, bytes, expectedSha256);
        return bytes;
    }

    private void verifyRemoteBytes(String path, String remoteName, byte[] bytes, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.trim().isEmpty()) return;
        String actual = sha256(bytes);
        if (!expectedSha256.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("Remote File SHA-256 校验失败: " + path + " -> " + remoteName + ", expected=" + expectedSha256 + ", actual=" + actual);
        }
    }

    private static final class ScriptDescriptor {
        final String id;
        final String name;
        final List<String> targets;
        final List<String> processes;
        final String runAt;
        final List<String> grants;
        final String remoteName;
        final String version;
        final String path;
        final String rootPath;
        final Map<String, String> files;
        final Map<String, String> fileHashes;
        final Map<String, String> assetFiles;
        final Map<String, String> assetFileHashes;
        final boolean hasSettings;
        final String settingsPath;
        final JSONObject settingsSchema;

        ScriptDescriptor(
                String id,
                String name,
                List<String> targets,
                List<String> processes,
                String runAt,
                List<String> grants,
                String remoteName,
                String version,
                String path,
                String rootPath,
                Map<String, String> files,
                Map<String, String> fileHashes,
                Map<String, String> assetFiles,
                Map<String, String> assetFileHashes,
                boolean hasSettings,
                String settingsPath,
                JSONObject settingsSchema
        ) {
            this.id = id;
            this.name = name;
            this.targets = targets;
            this.processes = processes;
            this.runAt = runAt;
            this.grants = grants;
            this.remoteName = remoteName;
            this.version = version == null || version.trim().isEmpty() ? "1.0.0" : version.trim();
            this.path = normalizeScriptPath(path);
            this.rootPath = normalizeScriptPath(rootPath);
            this.files = files == null ? new LinkedHashMap<>() : files;
            this.fileHashes = fileHashes == null ? new LinkedHashMap<>() : fileHashes;
            this.assetFiles = assetFiles == null ? new LinkedHashMap<>() : assetFiles;
            this.assetFileHashes = assetFileHashes == null ? new LinkedHashMap<>() : assetFileHashes;
            this.hasSettings = hasSettings && settingsSchema != null;
            this.settingsPath = normalizeScriptPath(settingsPath);
            this.settingsSchema = settingsSchema;
            if (!this.path.isEmpty() && !this.remoteName.isEmpty()) {
                this.files.put(this.path, this.remoteName);
            }
        }

        String displayName() {
            return name + "(" + id + ")";
        }

        String sourceName() {
            if (!path.isEmpty()) return path;
            return displayName();
        }

        boolean supportsPackage(String packageName) {
            return targets.isEmpty() || targets.contains("*") || targets.contains(packageName);
        }

        boolean supportsProcess(String processName) {
            return processes.isEmpty() || processes.contains("*") || processes.contains(processName);
        }

        boolean rawEnabled() {
            return hasGrant("xposed.raw") || hasGrant("xposed.full") || hasGrant("raw");
        }

        boolean dexApiEnabled() {
            return hasGrant("dex.full")
                    || hasGrant("dex.dump")
                    || hasGrant("dumpdex")
                    || hasGrant("dex.search")
                    || hasGrant("dex.read");
        }

        boolean dexDumpEnabled() {
            return hasGrant("dex.full") || hasGrant("dex.dump") || hasGrant("dumpdex");
        }

        boolean dexDebugEnabled() {
            return hasGrant("xhh.debug")
                    || hasGrant("xhh.internal.debug")
                    || hasGrant("internal.debug")
                    || hasGrant("dex.debug")
                    || hasGrant("dumpdex.debug")
                    || hasGrant("xhh.dex.debug");
        }

        private boolean hasGrant(String grant) {
            if (grant == null) return false;
            String target = grant.trim().toLowerCase(java.util.Locale.ROOT);
            for (String item : grants) {
                if (item != null && target.equals(item.trim().toLowerCase(java.util.Locale.ROOT))) return true;
            }
            return false;
        }
    }
}
