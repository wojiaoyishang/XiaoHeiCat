// ==LSPosedScript==
// @name         七点工具箱现代链式日志 Hook
// @id           qidian.toolbox.libxposed.log
// @version      2.0.0
// @author       XiaoHeiHook
// @description  仅记录七点工具箱 Application.onCreate 与 pc.a.d() 调用日志，不修改应用行为。
// @target       cn.am7code.tools
// @process      cn.am7code.tools
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// @grant        xposed.raw
// ==/LSPosedScript==

const TAG = "XHH-QidianDemo";

xposed.onPackageLoaded(function (param) {
    const packageName = String(param.getPackageName());
    const processName = String(env.processName);

    xposed.i(TAG, "loaded package=" + packageName + ", process=" + processName);

    if (packageName !== "cn.am7code.tools") {
        xposed.w(TAG, "skip unexpected package=" + packageName);
        return;
    }

    const appClass = Java.use("android.app.Application");
    const onCreate = appClass.getDeclaredMethod("onCreate");
    onCreate.setAccessible(true);

    xposed
        .hook(onCreate)
        .setPriority(xposed.PRIORITY_DEFAULT)
        .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
        .intercept(function (chain) {
            xposed.d(TAG, "Application.onCreate before");
            const result = chain.proceed();
            xposed.d(TAG, "Application.onCreate after; install pc.a.d log hook");

            try {
                const loader = param.getDefaultClassLoader();
                const targetClass = loader.loadClass("pc.a");
                const method = targetClass.getDeclaredMethod("d");
                method.setAccessible(true);

                xposed
                    .hook(method)
                    .setPriority(xposed.PRIORITY_DEFAULT)
                    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
                    .intercept(function (inner) {
                        xposed.d(TAG, "pc.a.d before, executable=" + inner.getExecutable());
                        const oldResult = inner.proceed();
                        xposed.d(TAG, "pc.a.d after, result=" + oldResult);
                        return oldResult;
                    });

                xposed.i(TAG, "pc.a.d log hook installed");
            } catch (e) {
                xposed.e(TAG, "install pc.a.d hook failed", e);
            }

            return result;
        });
});
