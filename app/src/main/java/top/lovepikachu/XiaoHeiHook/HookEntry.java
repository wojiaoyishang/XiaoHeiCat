package top.lovepikachu.XiaoHeiHook;

import android.content.Context;
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
import top.lovepikachu.XiaoHeiHook.script.TargetScriptCache;
import top.lovepikachu.XiaoHeiHook.dex.DexRuntimeRegistry;
import top.lovepikachu.XiaoHeiHook.dex.DexClassLoadDumper;

public class HookEntry extends XposedModule {
    private static final String TAG = "XiaoHeiHook-Entry";
    private static final String PREF_GROUP = "XiaoHeiHookSetting";
    private static final String SCRIPT_INDEX_JSON = "script_index_json";
    private static final String SCRIPT_HASH_CONFIG_JSON = "script_hash_config_json";

    private SharedPreferences prefs;
    private String processName = "unknown";
    private String activePackageNameForRemoteReads = "";

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

        cleanupTargetCacheWhenDisabled(packageName);

        List<ScriptDescriptor> scripts = readScriptIndexForPackage(packageName);
        applyRemoteHashConfig(packageName, scripts, readHashConfigForPackage(packageName));
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
                activePackageNameForRemoteReads = packageName;
                // Runtime cache reading is controlled only by LSPosed Remote Preferences.
                // The index may carry cache metadata for UI/sync, but it must not override the user's switch.
                boolean cacheEnabled = isTargetCacheEnabled(packageName);
                String targetCacheDir = targetScriptCacheDirFor(packageName, script);
                if (cacheEnabled) {
                    log(Log.INFO, TAG, "[XHH] target cache enabled: package=" + packageName + ", dir=" + targetCacheDir);
                }
                String expectedSha256 = expectedSha256For(packageName, script, script.sourceName(), script.remoteName, script.fileHashes.get(script.sourceName()));
                String source = loadScriptSource(packageName, script, expectedSha256, cacheEnabled, targetCacheDir);
                if (source == null) continue;
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
            List<ScriptDescriptor> scripts = readScriptIndexForPackage(packageName);
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
            List<ScriptDescriptor> scripts = readScriptIndexForPackage(packageName);
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

    private String loadScriptSource(String packageName, ScriptDescriptor script, String expectedSha256, boolean cacheEnabled, String targetCacheDir) throws Exception {
        expectedSha256 = expectedSha256For(packageName, script, script.sourceName(), script.remoteName, expectedSha256);
        File targetFilesDir = cacheEnabled ? resolveTargetFilesDir(packageName) : null;
        String cacheId = cacheIdFor(script.id, script.sourceName(), script.remoteName);
        if (cacheEnabled && targetFilesDir != null) {
            String local = TargetScriptCache.readIfHashMatch(targetFilesDir, packageName, targetCacheDir, cacheId, expectedSha256);
            if (local != null) {
                log(Log.INFO, TAG, "[XHH] use target private script cache: scriptId=" + script.id);
                return local;
            }
        }

        try {
            String source = JsHookRuntime.readRemoteText(openRemoteFile(script.remoteName));
            verifyRemoteText(script.sourceName(), script.remoteName, source, expectedSha256);
            if (cacheEnabled && targetFilesDir != null && expectedSha256 != null && !expectedSha256.trim().isEmpty()) {
                TargetScriptCache.write(targetFilesDir, packageName, targetCacheDir, cacheId, expectedSha256, source);
                log(Log.INFO, TAG, "[XHH] write target private script cache: scriptId=" + script.id + " sha256=" + expectedSha256 + " dir=" + targetCacheDir);
            }
            return source;
        } catch (Throwable remoteError) {
            if (cacheEnabled && targetFilesDir != null) {
                String fallback = TargetScriptCache.readIfHashMatch(targetFilesDir, packageName, targetCacheDir, cacheId, expectedSha256);
                if (fallback != null) {
                    log(Log.WARN, TAG, "[XHH] remote file missing, fallback to target cache: scriptId=" + script.id);
                    return fallback;
                }
            }
            log(Log.WARN, TAG, "[XHH] remote file missing and no target cache, skip script: " + script.id + ", error=" + remoteError);
            return null;
        }
    }

    private void applyRemoteHashConfig(String packageName, List<ScriptDescriptor> scripts, String rawConfig) {
        if (scripts == null || scripts.isEmpty()) return;
        for (ScriptDescriptor script : scripts) {
            if (script == null) continue;
            String entrySha = expectedSha256For(packageName, script, script.sourceName(), script.remoteName, script.fileHashes.get(script.sourceName()), rawConfig);
            if (isNonBlank(entrySha)) {
                script.fileHashes.put(script.sourceName(), entrySha);
                if (isNonBlank(script.path)) script.fileHashes.put(script.path, entrySha);
            }
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(script.files).entrySet()) {
                String path = entry.getKey();
                String remoteName = entry.getValue();
                String current = script.fileHashes.get(path);
                String sha = expectedSha256For(packageName, script, path, remoteName, current, rawConfig);
                if (isNonBlank(sha)) script.fileHashes.put(path, sha);
            }
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(script.assetFiles).entrySet()) {
                String path = entry.getKey();
                String remoteName = entry.getValue();
                String current = script.assetFileHashes.get(path);
                String sha = expectedSha256For(packageName, script, path, remoteName, current, rawConfig);
                if (isNonBlank(sha)) script.assetFileHashes.put(path, sha);
            }
        }
    }

    private String expectedSha256For(String packageName, ScriptDescriptor script, String path, String remoteName, String current) {
        String rawConfig = readHashConfigForPackage(packageName);
        return expectedSha256For(packageName, script, path, remoteName, current, rawConfig);
    }

    private String expectedSha256For(String packageName, ScriptDescriptor script, String path, String remoteName, String current, String rawConfig) {
        if (isNonBlank(current)) return current.trim();
        String scriptId = script == null ? "" : script.id;
        String fromRemoteConfig = lookupSha256InRemoteHashConfig(rawConfig, packageName, scriptId, path, remoteName);
        if (isNonBlank(fromRemoteConfig)) {
            log(Log.DEBUG, TAG, "[XHH] use remote hash config: package=" + packageName + ", scriptId=" + scriptId + ", path=" + path + ", remoteName=" + remoteName);
            return fromRemoteConfig.trim();
        }
        return current;
    }

    private static String lookupSha256InRemoteHashConfig(String rawConfig, String packageName, String scriptId, String path, String remoteName) {
        if (rawConfig == null || rawConfig.trim().isEmpty()) return null;
        String cleanPath = normalizeScriptPath(path);
        String cleanRemote = remoteName == null ? "" : remoteName.trim();
        String cleanPackage = packageName == null ? "" : packageName.trim();
        String cleanScriptId = scriptId == null ? "" : scriptId.trim();
        try {
            JSONObject root = new JSONObject(rawConfig);

            JSONObject byPackage = root.optJSONObject("byPackage");
            JSONObject packageObj = byPackage == null || cleanPackage.isEmpty() ? null : byPackage.optJSONObject(cleanPackage);
            String value = lookupInHashObject(packageObj == null ? null : packageObj.optJSONObject("byRemoteName"), cleanRemote);
            if (isNonBlank(value)) return value;
            value = lookupInHashObject(packageObj == null ? null : packageObj.optJSONObject("byPath"), cleanPath);
            if (isNonBlank(value)) return value;

            JSONObject byScriptId = root.optJSONObject("byScriptId");
            JSONObject scriptObj = byScriptId == null || cleanScriptId.isEmpty() ? null : byScriptId.optJSONObject(cleanScriptId);
            value = lookupInHashObject(scriptObj == null ? null : scriptObj.optJSONObject("remoteNames"), cleanRemote);
            if (isNonBlank(value)) return value;
            value = lookupInHashObject(scriptObj == null ? null : scriptObj.optJSONObject("files"), cleanPath);
            if (isNonBlank(value)) return value;
            value = lookupInHashObject(scriptObj == null ? null : scriptObj.optJSONObject("assets"), cleanPath);
            if (isNonBlank(value)) return value;
            if (scriptObj != null) {
                String entryPath = normalizeScriptPath(scriptObj.optString("entryPath", ""));
                String entryRemote = scriptObj.optString("entryRemoteName", "").trim();
                if ((!cleanPath.isEmpty() && cleanPath.equals(entryPath)) || (!cleanRemote.isEmpty() && cleanRemote.equals(entryRemote))) {
                    value = scriptObj.optString("entrySha256", "").trim();
                    if (isNonBlank(value)) return value;
                }
            }

            value = lookupInHashObject(root.optJSONObject("byRemoteName"), cleanRemote);
            if (isNonBlank(value)) return value;
            value = lookupInHashObject(root.optJSONObject("byPath"), cleanPath);
            if (isNonBlank(value)) return value;

            JSONArray files = root.optJSONArray("files");
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject file = files.optJSONObject(i);
                    if (file == null) continue;
                    String fileScriptId = file.optString("scriptId", "").trim();
                    String filePath = normalizeScriptPath(file.optString("path", ""));
                    String fileRemote = file.optString("remoteName", "").trim();
                    boolean scriptMatches = cleanScriptId.isEmpty() || fileScriptId.isEmpty() || cleanScriptId.equals(fileScriptId);
                    boolean pathMatches = !cleanPath.isEmpty() && cleanPath.equals(filePath);
                    boolean remoteMatches = !cleanRemote.isEmpty() && cleanRemote.equals(fileRemote);
                    if (scriptMatches && (pathMatches || remoteMatches)) {
                        value = file.optString("sha256", "").trim();
                        if (isNonBlank(value)) return value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String lookupInHashObject(JSONObject object, String key) {
        if (object == null || key == null || key.trim().isEmpty()) return null;
        String value = object.optString(key.trim(), "").trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.trim().isEmpty();
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

    private List<ScriptDescriptor> readScriptIndexForPackage(String packageName) {
        if (prefs == null || packageName == null || packageName.trim().isEmpty()) return new ArrayList<>();
        String raw = prefs.getString(scriptIndexJsonKey(packageName), null);
        if (raw == null || raw.trim().isEmpty()) {
            raw = prefs.getString(SCRIPT_INDEX_JSON, "[]"); // legacy migration fallback
            log(Log.DEBUG, TAG, "[XHH] package script_index_json missing; fallback to legacy index: package=" + packageName);
        }
        return parseScriptIndex(raw);
    }

    private String readHashConfigForPackage(String packageName) {
        if (prefs == null || packageName == null || packageName.trim().isEmpty()) return "{}";
        String raw = prefs.getString(scriptHashConfigJsonKey(packageName), null);
        if (raw == null || raw.trim().isEmpty()) raw = prefs.getString(SCRIPT_HASH_CONFIG_JSON, "{}");
        return raw == null || raw.trim().isEmpty() ? "{}" : raw;
    }

    private static String scriptIndexJsonKey(String packageName) {
        return packageName + "_" + SCRIPT_INDEX_JSON;
    }

    private static String scriptHashConfigJsonKey(String packageName) {
        return packageName + "_" + SCRIPT_HASH_CONFIG_JSON;
    }

    private static String appEnabledKey(String packageName) {
        return "app_enabled_" + packageName;
    }

    private static String scriptEnabledKey(String packageName, String scriptId) {
        return "script_enabled_" + packageName + "_" + scriptId;
    }

    private static String appCacheToPrivateDirKey(String packageName) {
        return "cache_scripts_to_private_dir_" + packageName;
    }

    private static String appTargetScriptCacheDirKey(String packageName) {
        return "target_script_cache_dir_" + packageName;
    }

    private boolean isTargetCacheEnabled(String packageName) {
        // Target private cache is valid only when LSPosed Remote Preferences says it is enabled.
        // If Remote Preferences is unavailable, do not trust any local cache.
        return prefs != null && packageName != null && !packageName.trim().isEmpty() && prefs.getBoolean(appCacheToPrivateDirKey(packageName), true);
    }

    private void cleanupTargetCacheWhenDisabled(String packageName) {
        if (isTargetCacheEnabled(packageName)) return;
        File targetFilesDir = resolveTargetFilesDir(packageName);
        if (targetFilesDir == null) return;
        String configuredDir = targetScriptCacheDirFor(packageName, null);
        boolean cleaned = TargetScriptCache.deleteRoot(targetFilesDir, configuredDir);
        if (!TargetScriptCache.DEFAULT_ROOT_DIR.equals(configuredDir)) {
            cleaned = TargetScriptCache.deleteRoot(targetFilesDir, TargetScriptCache.DEFAULT_ROOT_DIR) || cleaned;
        }
        if (cleaned) {
            log(Log.INFO, TAG, "[XHH] target private script cache disabled; cleaned cache dir: package=" + packageName + ", dir=" + configuredDir);
        }
    }

    private String targetScriptCacheDirFor(String packageName, ScriptDescriptor script) {
        String fromPrefs = prefs == null ? null : prefs.getString(appTargetScriptCacheDirKey(packageName), null);
        if (fromPrefs != null && !fromPrefs.trim().isEmpty()) {
            return TargetScriptCache.normalizeRootDir(fromPrefs);
        }
        if (script != null) {
            String fromIndex = script.targetScriptCacheDir(packageName);
            if (fromIndex != null && !fromIndex.trim().isEmpty()) {
                return TargetScriptCache.normalizeRootDir(fromIndex);
            }
        }
        return TargetScriptCache.DEFAULT_ROOT_DIR;
    }

    private static String cacheIdFor(String scriptId, String path, String remoteName) {
        String key = scriptId == null ? "" : scriptId.trim();
        String cleanPath = normalizeScriptPath(path);
        if (!cleanPath.isEmpty()) key += "_" + cleanPath;
        String cleanRemote = remoteName == null ? "" : remoteName.trim();
        if (!cleanRemote.isEmpty()) key += "_" + cleanRemote;
        return key.isEmpty() ? "unnamed" : key;
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
                        obj.optJSONObject("settingsSchema"),
                        obj.optBoolean("cacheScriptsToPrivateDir", false),
                        toStringList(obj.optJSONArray("cacheScriptsToPrivateDirPackages")),
                        obj.optString("targetScriptCacheDir", ""),
                        toStringMap(obj.optJSONObject("targetScriptCacheDirByPackage"))
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

    private static Map<String, String> toStringMap(JSONObject object) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (object == null) return map;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = object.optString(key, "").trim();
            if (key != null && !key.trim().isEmpty() && !value.isEmpty()) {
                map.put(key.trim(), value);
            }
        }
        return map;
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

    /**
     * Resolve the hooked target app's filesDir without calling createPackageContext().
     *
     * Calling Context#createPackageContext() from package-loaded can re-enter
     * ActivityThread#getPackageInfo(), which lets LSPosed dispatch package-loaded
     * again and causes recursive hook execution. So we only use an already-created
     * target Context, or reflect the current ActivityThread bound LoadedApk dataDir.
     * The returned path is still written by the target process itself; XiaoHeiHook's
     * app process never copies files into another app's private directory.
     */
    private File resolveTargetFilesDir(String packageName) {
        String expected = packageName == null ? "" : packageName.trim();
        if (expected.isEmpty()) return null;

        Context current = currentApplicationContext();
        if (isUsableTargetContext(current, expected)) return current.getFilesDir();

        Context threadApp = activityThreadApplicationContext();
        if (isUsableTargetContext(threadApp, expected)) return threadApp.getFilesDir();

        File boundFilesDir = activityThreadBoundFilesDir(expected);
        if (isUsableTargetFilesDir(boundFilesDir, expected)) return boundFilesDir;

        log(Log.WARN, TAG, "[XHH] target private cache unavailable: cannot resolve target filesDir, package=" + expected
                + ", current=" + describeContext(current)
                + ", threadApp=" + describeContext(threadApp)
                + ", boundFilesDir=" + describeFile(boundFilesDir));
        return null;
    }

    private Context currentApplicationContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Context activityThreadApplicationContext() {
        try {
            Object thread = currentActivityThread();
            if (thread == null) return null;
            java.lang.reflect.Method getApplication = thread.getClass().getDeclaredMethod("getApplication");
            getApplication.setAccessible(true);
            Object application = getApplication.invoke(thread);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object currentActivityThread() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            return currentActivityThread.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private File activityThreadBoundFilesDir(String packageName) {
        try {
            Object thread = currentActivityThread();
            if (thread == null) return null;
            Object boundApplication = readField(thread, "mBoundApplication");
            if (boundApplication == null) return null;

            Object appInfo = readField(boundApplication, "appInfo");
            String boundPackage = applicationInfoPackageName(appInfo);
            if (!packageName.equals(boundPackage)) {
                Object loadedApk = readField(boundApplication, "info");
                boundPackage = loadedApkPackageName(loadedApk);
                if (!packageName.equals(boundPackage)) return null;
            }

            Object loadedApk = readField(boundApplication, "info");
            File dataDir = loadedApkDataDir(loadedApk);
            if (dataDir == null) dataDir = applicationInfoDataDir(appInfo);
            return dataDir == null ? null : new File(dataDir, "files");
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "[XHH] resolve ActivityThread bound filesDir failed: package=" + packageName + ", error=" + t);
            return null;
        }
    }

    private Object readField(Object owner, String name) {
        if (owner == null || name == null) return null;
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private Object invokeNoArg(Object owner, String name) {
        if (owner == null || name == null) return null;
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(owner);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private String applicationInfoPackageName(Object appInfo) {
        if (appInfo instanceof android.content.pm.ApplicationInfo) {
            return ((android.content.pm.ApplicationInfo) appInfo).packageName;
        }
        return null;
    }

    private File applicationInfoDataDir(Object appInfo) {
        if (appInfo instanceof android.content.pm.ApplicationInfo) {
            String dataDir = ((android.content.pm.ApplicationInfo) appInfo).dataDir;
            return dataDir == null || dataDir.trim().isEmpty() ? null : new File(dataDir);
        }
        return null;
    }

    private String loadedApkPackageName(Object loadedApk) {
        Object packageName = invokeNoArg(loadedApk, "getPackageName");
        if (packageName instanceof String && !((String) packageName).trim().isEmpty()) return (String) packageName;
        Object fieldValue = readField(loadedApk, "mPackageName");
        return fieldValue instanceof String ? (String) fieldValue : null;
    }

    private File loadedApkDataDir(Object loadedApk) {
        Object dataDirFile = invokeNoArg(loadedApk, "getDataDirFile");
        if (dataDirFile instanceof File) return (File) dataDirFile;
        Object dataDirString = invokeNoArg(loadedApk, "getDataDir");
        if (dataDirString instanceof String && !((String) dataDirString).trim().isEmpty()) return new File((String) dataDirString);
        Object fieldFile = readField(loadedApk, "mDataDirFile");
        if (fieldFile instanceof File) return (File) fieldFile;
        Object fieldString = readField(loadedApk, "mDataDir");
        if (fieldString instanceof String && !((String) fieldString).trim().isEmpty()) return new File((String) fieldString);
        return null;
    }

    private boolean isUsableTargetContext(Context context, String packageName) {
        if (context == null || packageName == null || packageName.trim().isEmpty()) return false;
        try {
            if (!packageName.equals(context.getPackageName())) return false;
            File filesDir = context.getFilesDir();
            if (filesDir == null) return false;
            android.content.pm.ApplicationInfo info = context.getApplicationInfo();
            String dataDir = info == null ? null : info.dataDir;
            if (dataDir != null && !dataDir.trim().isEmpty()) {
                File canonicalFiles = filesDir.getCanonicalFile();
                File canonicalData = new File(dataDir).getCanonicalFile();
                String filesPath = canonicalFiles.getPath();
                String dataPath = canonicalData.getPath();
                if (!filesPath.equals(dataPath) && !filesPath.startsWith(dataPath + File.separator)) {
                    log(Log.WARN, TAG, "[XHH] reject target context with suspicious filesDir: package=" + packageName + ", filesDir=" + filesPath + ", dataDir=" + dataPath);
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "[XHH] reject target context: package=" + packageName + ", context=" + describeContext(context), t);
            return false;
        }
    }

    private boolean isUsableTargetFilesDir(File filesDir, String packageName) {
        if (filesDir == null || packageName == null || packageName.trim().isEmpty()) return false;
        try {
            File canonicalFiles = filesDir.getCanonicalFile();
            File parent = canonicalFiles.getParentFile();
            if (parent == null) return false;
            String filesPath = canonicalFiles.getPath();
            String dataPath = parent.getPath();
            if (!"files".equals(canonicalFiles.getName())) return false;
            if (!dataPath.endsWith(File.separator + packageName) && !dataPath.endsWith("/" + packageName)) {
                log(Log.WARN, TAG, "[XHH] reject bound filesDir with unexpected package path: package=" + packageName + ", filesDir=" + filesPath);
                return false;
            }
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "[XHH] reject target filesDir: package=" + packageName + ", filesDir=" + describeFile(filesDir), t);
            return false;
        }
    }

    private String describeFile(File file) {
        if (file == null) return "null";
        try {
            return file.getCanonicalPath();
        } catch (Throwable ignored) {
            return file.getAbsolutePath();
        }
    }

    private String describeContext(Context context) {
        if (context == null) return "null";
        try {
            File filesDir = context.getFilesDir();
            return context.getPackageName() + " filesDir=" + (filesDir == null ? "null" : filesDir.getAbsolutePath());
        } catch (Throwable t) {
            return context.getClass().getName() + " error=" + t.getClass().getSimpleName();
        }
    }

    public String readRemoteTextFile(@NonNull String remoteName) throws Exception {
        return readRemoteTextFile(remoteName, null, remoteName);
    }

    public String readRemoteTextFile(@NonNull String remoteName, String expectedSha256, String path) throws Exception {
        String packageName = activePackageNameForRemoteReads == null ? "" : activePackageNameForRemoteReads;
        expectedSha256 = expectedSha256For(packageName, null, path == null ? remoteName : path, remoteName, expectedSha256);
        boolean cacheEnabled = !packageName.isEmpty() && isTargetCacheEnabled(packageName);
        String targetCacheDir = targetScriptCacheDirFor(packageName, null);
        File targetFilesDir = cacheEnabled ? resolveTargetFilesDir(packageName) : null;
        String cacheId = cacheIdFor(packageName, path == null ? remoteName : path, remoteName);
        if (cacheEnabled && targetFilesDir != null) {
            String local = TargetScriptCache.readIfHashMatch(targetFilesDir, packageName, targetCacheDir, cacheId, expectedSha256);
            if (local != null) return local;
        }
        try {
            String source = JsHookRuntime.readRemoteText(openRemoteFile(remoteName));
            verifyRemoteText(path == null ? remoteName : path, remoteName, source, expectedSha256);
            if (cacheEnabled && targetFilesDir != null && expectedSha256 != null && !expectedSha256.trim().isEmpty()) {
                TargetScriptCache.write(targetFilesDir, packageName, targetCacheDir, cacheId, expectedSha256, source);
            }
            return source;
        } catch (Throwable remoteError) {
            if (cacheEnabled && targetFilesDir != null) {
                String fallback = TargetScriptCache.readIfHashMatch(targetFilesDir, packageName, targetCacheDir, cacheId, expectedSha256);
                if (fallback != null) return fallback;
            }
            if (remoteError instanceof Exception) throw (Exception) remoteError;
            throw new RuntimeException(remoteError);
        }
    }

    public byte[] readRemoteBinaryFile(@NonNull String remoteName, String expectedSha256, String path) throws Exception {
        String packageName = activePackageNameForRemoteReads == null ? "" : activePackageNameForRemoteReads;
        expectedSha256 = expectedSha256For(packageName, null, path == null ? remoteName : path, remoteName, expectedSha256);
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
        final boolean cacheScriptsToPrivateDir;
        final List<String> cacheScriptsToPrivateDirPackages;
        final String targetScriptCacheDir;
        final Map<String, String> targetScriptCacheDirByPackage;

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
                JSONObject settingsSchema,
                boolean cacheScriptsToPrivateDir,
                List<String> cacheScriptsToPrivateDirPackages,
                String targetScriptCacheDir,
                Map<String, String> targetScriptCacheDirByPackage
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
            this.cacheScriptsToPrivateDir = cacheScriptsToPrivateDir;
            this.cacheScriptsToPrivateDirPackages = cacheScriptsToPrivateDirPackages == null ? new ArrayList<>() : cacheScriptsToPrivateDirPackages;
            this.targetScriptCacheDir = TargetScriptCache.normalizeRootDir(targetScriptCacheDir);
            this.targetScriptCacheDirByPackage = targetScriptCacheDirByPackage == null ? new LinkedHashMap<>() : targetScriptCacheDirByPackage;
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

        boolean cacheScriptsToPrivateDir(String packageName) {
            return cacheScriptsToPrivateDir || cacheScriptsToPrivateDirPackages.contains(packageName) || cacheScriptsToPrivateDirPackages.contains("*");
        }

        String targetScriptCacheDir(String packageName) {
            if (packageName != null) {
                String exact = targetScriptCacheDirByPackage.get(packageName);
                if (exact != null && !exact.trim().isEmpty()) return exact;
            }
            String wildcard = targetScriptCacheDirByPackage.get("*");
            if (wildcard != null && !wildcard.trim().isEmpty()) return wildcard;
            return targetScriptCacheDir;
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
