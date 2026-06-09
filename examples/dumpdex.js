// ==LSPosedScript==
// @name         通用脱壳 DumpDex
// @id           generic.dumpdex.only
// @version      1.1.0
// @description  通用脱壳脚本：在 Application.attach 后使用真实 app ClassLoader dump 当前加载的全部 dex。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// @grant        dex.dump
// ==/LSPosedScript==

const TAG = "DumpDexOnly";

let executed = false;

function stringify(value) {
  try {
    if (value === null) return "null";
    if (typeof value === "undefined") return "undefined";
    if (typeof value === "string") return value;
    return JSON.stringify(value);
  } catch (e) {
    return String(value);
  }
}

function logObjectKind(name, value) {
  try {
    const kind = xhh.objectKind(value);
    xposed.d(
      TAG,
      name +
        " kind=" +
        kind.kind +
        " isJsObject=" +
        kind.isJsObject +
        " isJavaObject=" +
        kind.isJavaObject +
        " rawClass=" +
        kind.rawClass +
        " javaClass=" +
        kind.javaClass
    );
  } catch (e) {
    xposed.d(TAG, name + " kind check failed: " + e);
  }
}

xposed.onPackageLoaded(function (param) {
  const packageName = String(param.getPackageName());
  const processName = String(env.processName || "");

  xposed.i(TAG, "loaded package=" + packageName + " process=" + processName);

  const Application = Java.type("android.app.Application");
  const ContextClass = Java.type("android.content.Context");

  const attach = Application.getDeclaredMethod("attach", ContextClass);
  attach.setAccessible(true);

  xposed
    .hook(attach)
    .setPriority(xposed.PRIORITY_DEFAULT)
    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
    .intercept(function (chain) {
      const context = chain.getArg(0);

      xposed.d(TAG, "Application.attach before");

      const result = chain.proceed();

      try {
        if (executed) {
          xposed.d(TAG, "dump already executed, skip");
          return result;
        }

        executed = true;

        const appPackage = String(context.getPackageName());
        const loader = context.getClassLoader();

        const outputDir =
          "/data/user/0/" +
          appPackage +
          "/code_cache/xhh_dumpdex";

        xposed.i(TAG, "Application.attach after");
        xposed.i(TAG, "package=" + appPackage);
        xposed.i(TAG, "process=" + processName);
        xposed.i(TAG, "appClassLoader=" + loader);
        xposed.i(TAG, "dump all dex start outputDir=" + outputDir);

        logObjectKind("loader", loader);

        const ret = dex.dumpDexCookies({
          loader: loader,
          cookieDir: outputDir,
          outputDir: outputDir,
          clearCookieDir: true,
          clearOutputDir: true,
          maxDexBytes: 512 * 1024 * 1024
        });

        logObjectKind("dumpDexCookies.ret", ret);

        if (!xhh.isJsObject(ret)) {
          xposed.w(TAG, "dumpDexCookies returned non-JS object: " + String(ret));
          return result;
        }

        if (!ret.ok) {
          xposed.e(TAG, "dump all dex failed ret=" + stringify(ret));
          return result;
        }

        const paths = ret.paths;

        xposed.i(TAG, "dump all dex finished");
        xposed.i(TAG, "count=" + ret.count);
        xposed.i(TAG, "outputDir=" + ret.outputDir);

        if (!paths || paths.length === 0) {
          xposed.w(TAG, "no dex dumped");
          return result;
        }

        for (let i = 0; i < paths.length; i++) {
          xposed.i(TAG, "dumped[" + i + "]=" + paths[i]);
        }
      } catch (e) {
        xposed.e(TAG, "dump all dex failed", e);
      }

      return result;
    });
});