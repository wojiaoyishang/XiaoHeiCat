// ==LSPosedScript==
// @name         DumpDex 之后通过 Smali 搜索找到目标类
// @id           generic.dumpdex.smali.search
// @version      1.1.0
// @description  在 Application.attach 后 dump 当前加载 dex，然后遍历每个 dex 文件按 smali 特征搜索目标方法；找到第一个目标后立即停止。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// @grant        dex.dump
// @grant        dex.find
// ==/LSPosedScript==

const TAG = "DumpDexSmaliSearch";

let executed = false;

const SMALI_FEATURES = [
  'const-string "am7_dev_vip_override"',
  "Lcom/blankj/utilcode/util/r;->a(Ljava/lang/String;)Lcom/blankj/utilcode/util/r;",
  "Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
  'const-string "getString(...)"',
  'const-string "vip"',
  'const-string "nonvip"',
  "Lpc/a;->c()Z",
  "Lpc/a;->b()Lwork/am7code/common/userinfo/model/Am7UserInfo;",
  "Lwork/am7code/common/userinfo/model/Am7UserInfo;->getVip_start_time()Ljava/lang/Long;",
  "Lwork/am7code/common/userinfo/model/Am7UserInfo;->getVip_duration()Ljava/lang/Long;",
  "Ljava/lang/System;->currentTimeMillis()J"
];

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

function dumpDexAndSearch(loader, appPackage, processName) {
  const outputDir =
    "/data/user/0/" +
    appPackage +
    "/code_cache/xhh_dumpdex";

  xposed.i(TAG, "dump dex start");
  xposed.i(TAG, "package=" + appPackage);
  xposed.i(TAG, "process=" + processName);
  xposed.i(TAG, "outputDir=" + outputDir);
  xposed.i(TAG, "appClassLoader=" + loader);

  const dumpRet = dex.dumpDexCookies({
    loader: loader,
    cookieDir: outputDir,
    outputDir: outputDir,
    clearCookieDir: true,
    clearOutputDir: true,
    maxDexBytes: 512 * 1024 * 1024
  });

  if (!xhh.isJsObject(dumpRet)) {
    xposed.e(TAG, "dumpDexCookies returned non-JS object: " + String(dumpRet));
    return;
  }

  if (!dumpRet.ok) {
    xposed.e(TAG, "dumpDexCookies failed ret=" + stringify(dumpRet));
    return;
  }

  const paths = dumpRet.paths;

  xposed.i(TAG, "dump dex finished count=" + dumpRet.count);

  if (!paths || paths.length === 0) {
    xposed.w(TAG, "no dumped dex paths");
    return;
  }

  let found = null;

  xhh.each(paths, function (item, i) {
    let path = String(item);

    xposed.i(TAG, "search smali in dex[" + i + "]=" + path);

    try {
      let results = dex.findMethods({
        path: path,
        smaliContains: SMALI_FEATURES,
        limit: 1
      });

      if (!results || results.length === 0) {
        xposed.d(TAG, "no match in dex[" + i + "]");
        return true;
      }

      let m = results[0];

      found = {
        dexIndex: i,
        dexPath: path,
        className: String(m.className || ""),
        methodName: String(m.methodName || ""),
        proto: String(m.proto || ""),
        descriptor: String(m.descriptor || ""),
        score: m.score,
        reasons: m.reasons,
        smaliHead: m.smaliHead
      };

      xposed.i(TAG, "========== SMALI MATCH FOUND ==========");
      xposed.i(TAG, "dexIndex=" + found.dexIndex);
      xposed.i(TAG, "dexPath=" + found.dexPath);
      xposed.i(TAG, "className=" + found.className);
      xposed.i(TAG, "methodName=" + found.methodName);
      xposed.i(TAG, "proto=" + found.proto);
      xposed.i(TAG, "descriptor=" + found.descriptor);
      xposed.i(TAG, "score=" + found.score);

      if (found.reasons) {
        xposed.i(TAG, "reasons=" + stringify(found.reasons));
      }

      if (found.smaliHead) {
        xposed.i(TAG, "smaliHead:\n" + found.smaliHead);
      }

      xposed.i(TAG, "======================================");

      return false;
    } catch (e) {
      xposed.e(TAG, "search smali failed path=" + path, e);
      return true;
    }
  });

  if (found) {
    xposed.i(TAG, "========== SMALI SEARCH SUMMARY ==========");
    xposed.i(TAG, "found=true");
    xposed.i(TAG, "foundDexIndex=" + found.dexIndex);
    xposed.i(TAG, "foundDexPath=" + found.dexPath);
    xposed.i(TAG, "foundClassName=" + found.className);
    xposed.i(TAG, "foundMethodName=" + found.methodName);
    xposed.i(TAG, "foundProto=" + found.proto);
    xposed.i(TAG, "foundDescriptor=" + found.descriptor);
    xposed.i(TAG, "=========================================");
  } else {
    xposed.w(TAG, "========== SMALI SEARCH SUMMARY ==========");
    xposed.w(TAG, "found=false");
    xposed.w(TAG, "searchedDexCount=" + paths.length);
    xposed.w(TAG, "no dex matched the given smali features");
    xposed.w(TAG, "=========================================");
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
          xposed.d(TAG, "already executed, skip");
          return result;
        }

        executed = true;

        const appPackage = String(context.getPackageName());
        const loader = context.getClassLoader();

        xposed.i(TAG, "Application.attach after");

        dumpDexAndSearch(loader, appPackage, processName);
      } catch (e) {
        xposed.e(TAG, "dump dex and smali search failed", e);
      }

      return result;
    });
});