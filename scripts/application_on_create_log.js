// ==LSPosedScript==
// @name         Application.onCreate 现代链式日志模板
// @id           sample.application.oncreate.modern.log
// @version      2.0.0
// @author       XiaoHeiHook
// @description  使用 libxposed 风格链式 API 记录 Application.onCreate，不修改应用行为。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// @grant        xposed.raw
// ==/LSPosedScript==

const TAG = "XHH-Sample";

xposed.onPackageLoaded(function (param) {
    xposed.i(TAG, "loaded package=" + param.getPackageName() + ", process=" + env.processName);

    const appClass = Java.use("android.app.Application");
    const onCreate = appClass.getDeclaredMethod("onCreate");
    onCreate.setAccessible(true);

    xposed
        .hook(onCreate)
        .setPriority(xposed.PRIORITY_DEFAULT)
        .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
        .intercept(function (chain) {
            xposed.d(TAG, "Application.onCreate before: " + param.getPackageName());
            const result = chain.proceed();
            xposed.d(TAG, "Application.onCreate after: " + param.getPackageName());
            return result;
        });
});
