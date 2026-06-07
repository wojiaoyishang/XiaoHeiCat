// ==LSPosedScript==
// @name         全部配置项测试脚本
// @id           xhh.settings.all.types.test
// @version      1.0.0
// @author       XiaoHeiHook
// @description  用于测试 settings.json 12 种动态配置项、按应用独立保存以及 JS settings API。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// ==/LSPosedScript==

const tag = settings.get("tag", "XHH-SettingsTest");
const enabled = settings.get("enabled", true);
const mode = settings.get("mode", "basic");
const all = settings.all();

console.log("[settings test] package=", env.packageName, "process=", env.processName);
console.log("[settings test] raw=", settings.raw);
console.log("[settings test] tag=", tag, "enabled=", enabled, "mode=", mode);
console.log("[settings test] all=", JSON.stringify(all));

xposed.onPackageLoaded(function (param) {
  const packageName = String(param.getPackageName());
  const currentTag = settings.get("tag", "XHH-SettingsTest");
  const cfg = settings.all();

  xposed.i(currentTag, "settings all-types test loaded: " + packageName + " / " + env.processName);
  xposed.i(currentTag, "enabled=" + settings.get("enabled", true)
    + ", strictMode=" + settings.get("strictMode", false)
    + ", maxLogLength=" + settings.get("maxLogLength", 240)
    + ", radioMode=" + settings.get("radioMode", "auto"));
  xposed.i(currentTag, "headers=" + JSON.stringify(settings.get("headers", [])));
  xposed.i(currentTag, "rules=" + JSON.stringify(settings.get("rules", [])));

  if (!settings.get("enabled", true)) {
    xposed.w(currentTag, "script logic disabled by settings");
    return;
  }

  if (settings.get("mode", "basic") !== "silent") {
    xposed.d(currentTag, "settings keys=" + Object.keys(cfg).join(","));
  }
});
