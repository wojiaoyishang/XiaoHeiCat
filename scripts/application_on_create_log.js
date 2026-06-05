// ==LSPosedScript==
// @name         Application.onCreate 日志模板
// @id           sample.application.oncreate.log
// @version      1.0.0
// @author       XiaoHeiHook
// @description  安全模板：在已启用的目标应用中记录 Application.onCreate，不修改应用行为。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.hook
// ==/LSPosedScript==

console.log("script loaded for " + env.packageName + " / " + env.processName);

xposed.hookMethod({
    className: "android.app.Application",
    methodName: "onCreate",
    parameterTypes: [],
    after: function (param) {
        console.log("Application.onCreate finished: " + env.packageName);
    }
});
