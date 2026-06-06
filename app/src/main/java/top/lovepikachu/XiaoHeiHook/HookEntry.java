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
                JsHookRuntime runtime = new JsHookRuntime(
                        this,
                        packageName,
                        processName,
                        classLoader,
                        runAt,
                        rawParam,
                        script.rawEnabled()
                );
                runtime.evaluate(script.displayName(), script.sourceName(), source);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "加载脚本失败: " + script.id + " -> " + packageName + " @" + runAt, t);
            }
        }
    }

    private static String appEnabledKey(String packageName) {
        return "app_enabled_" + packageName;
    }

    private static String scriptEnabledKey(String packageName, String scriptId) {
        return "script_enabled_" + packageName + "_" + scriptId;
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
                        obj.optString("path", obj.optString("scriptPath", ""))
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

    private static final class ScriptDescriptor {
        final String id;
        final String name;
        final List<String> targets;
        final List<String> processes;
        final String runAt;
        final List<String> grants;
        final String remoteName;
        final String path;

        ScriptDescriptor(
                String id,
                String name,
                List<String> targets,
                List<String> processes,
                String runAt,
                List<String> grants,
                String remoteName,
                String path
        ) {
            this.id = id;
            this.name = name;
            this.targets = targets;
            this.processes = processes;
            this.runAt = runAt;
            this.grants = grants;
            this.remoteName = remoteName;
            this.path = path == null ? "" : path.trim();
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
