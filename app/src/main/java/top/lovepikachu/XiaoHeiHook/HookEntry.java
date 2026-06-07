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
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import io.github.libxposed.api.XposedModule;
import top.lovepikachu.XiaoHeiHook.script.JsHookRuntime;

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
        dispatchScripts(
                param.getPackageName(),
                loader,
                JsHookRuntime.EVENT_PACKAGE_READY,
                param
        );
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
                        mergedSettingsJson(packageName, script)
                );
                runtime.evaluate(script.displayName(), script.sourceName(), source);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "加载脚本失败: " + script.id + " -> " + packageName + " @" + runAt, t);
            }
        }
    }

    private void verifyRemoteText(String path, String remoteName, String source, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.trim().isEmpty()) return;
        String actual = sha256(source == null ? "" : source);
        if (!expectedSha256.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("Remote File SHA-256 校验失败: " + path + " -> " + remoteName + ", expected=" + expectedSha256 + ", actual=" + actual);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
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
                        obj.optString("path", obj.optString("scriptPath", "")),
                        parseFiles(obj.optJSONArray("files"), obj.optString("path", obj.optString("scriptPath", "")), remoteName),
                        parseFileHashes(obj.optJSONArray("files"), obj.optString("path", obj.optString("scriptPath", "")), obj.optString("sha256", "")),
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

    private static final class ScriptDescriptor {
        final String id;
        final String name;
        final List<String> targets;
        final List<String> processes;
        final String runAt;
        final List<String> grants;
        final String remoteName;
        final String path;
        final Map<String, String> files;
        final Map<String, String> fileHashes;
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
                String path,
                Map<String, String> files,
                Map<String, String> fileHashes,
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
            this.path = normalizeScriptPath(path);
            this.files = files == null ? new LinkedHashMap<>() : files;
            this.fileHashes = fileHashes == null ? new LinkedHashMap<>() : fileHashes;
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
            return grants.contains("xposed.raw") || grants.contains("xposed.full") || grants.contains("raw");
        }
    }
}
