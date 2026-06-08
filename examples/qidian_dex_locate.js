// ==LSPosedScript==
// @name         七点工具箱 pc.a.d 定位与特征检查
// @id           qidian.locate.pc.a.d.method
// @version      1.0.0
// @description  定位已脱壳 dex 中的 pc.a.d()Z，并获取运行时 Method 与 dex 方法特征。
// @target       cn.am7code.tools
// @process      cn.am7code.tools
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// @grant        dex.dump
// ==/LSPosedScript==

const TAG = "QidianLocatePcAD";

const PACKAGE_NAME = "cn.am7code.tools";
const TARGET_CLASS = "pc.a";
const TARGET_METHOD = "d";
const TARGET_PROTO = "()Z";

const FEATURES = [
  "am7_dev_vip_override",
  "getString(...)",
  "vip",
  "nonvip"
];

const INVOKE_CONTAINS = [
  "Landroid/content/SharedPreferences;->getString(",
  "Lwork/am7code/common/userinfo/model/Am7UserInfo;->getVip_start_time(",
  "Lwork/am7code/common/userinfo/model/Am7UserInfo;->getVip_duration(",
  "Ljava/lang/System;->currentTimeMillis("
];

let executed = false;

xposed.onPackageLoaded(function (param) {
  const pkg = String(param.getPackageName());
  if (pkg !== PACKAGE_NAME) return;

  xposed.i(TAG, "script loaded package=" + pkg + " process=" + env.processName);

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
      const result = chain.proceed();

      if (executed) return result;
      executed = true;

      try {
        const loader = context.getClassLoader();
        const dumpDir =
          "/data/user/0/" + PACKAGE_NAME + "/code_cache/xhh_dumpdex";

        xposed.i(TAG, "appClassLoader=" + loader);
        xposed.i(TAG, "dumpDir=" + dumpDir);

        // 1. 获取运行时 Java Method 对象
        const TargetClass = loader.loadClass(TARGET_CLASS);
        const runtimeMethod = TargetClass.getDeclaredMethod(TARGET_METHOD);
        runtimeMethod.setAccessible(true);

        xposed.i(TAG, "runtime Method=" + runtimeMethod);
        xposed.i(TAG, "declaringClass=" + runtimeMethod.getDeclaringClass());
        xposed.i(TAG, "returnType=" + runtimeMethod.getReturnType());

        // 2. 在已脱壳 dex 目录中定位 pc.a.d()Z 所在 dex
        const located = dex.locateMethodInCookieDumps({
          dir: dumpDir,
          prefix: "cookie_",
          className: TARGET_CLASS,
          methodName: TARGET_METHOD,
          proto: TARGET_PROTO,
          maxDexBytes: 512 * 1024 * 1024
        });

        const found = boolGet(located, "found");
        xposed.i(TAG, "locate found=" + found + " raw=" + located);

        if (!found) {
          xposed.w(TAG, "pc.a.d()Z not found in dump dir=" + dumpDir);
          return result;
        }

        const dexPath = strGet(located, "path");
        xposed.i(TAG, "target dex path=" + dexPath);

        // 3. 检查该方法的字符串 / invoke 特征
        const inspected = dex.inspectMethodInFile({
          path: dexPath,
          className: TARGET_CLASS,
          methodName: TARGET_METHOD,
          proto: TARGET_PROTO,
          strings: FEATURES,
          invokeContains: INVOKE_CONTAINS,
          maxDexBytes: 512 * 1024 * 1024
        });

        xposed.i(TAG, "inspect found=" + boolGet(inspected, "found"));
        xposed.i(TAG, "classFound=" + boolGet(inspected, "classFound"));
        xposed.i(TAG, "methodFound=" + boolGet(inspected, "methodFound"));
        xposed.i(TAG, "featuresOk=" + boolGet(inspected, "featuresOk"));
        xposed.i(TAG, "descriptor=" + strGet(inspected, "descriptor"));
        xposed.i(TAG, "static=" + boolGet(inspected, "static"));

        xposed.i(TAG, "strings=" + strGet(inspected, "strings"));
        xposed.i(TAG, "invokes=" + strGet(inspected, "invokes"));
        xposed.i(TAG, "missingStrings=" + strGet(inspected, "missingStrings"));
        xposed.i(TAG, "missingInvokeContains=" + strGet(inspected, "missingInvokeContains"));
        xposed.i(TAG, "smali.head=" + strGet(inspected, "smaliHead"));

        // 这里 runtimeMethod 就是你要的 Java 反射 Method 对象
        // inspected 是 dex 方法体分析结果
      } catch (e) {
        xposed.e(TAG, "locate pc.a.d failed", e);
      }

      return result;
    });
});

function mapGet(map, key) {
  try {
    if (map === null || map === undefined) return null;
    return map.get(String(key));
  } catch (e) {
    return null;
  }
}

function strGet(map, key) {
  const v = mapGet(map, key);
  if (v === null || v === undefined) return "";
  return String(v);
}

function boolGet(map, key) {
  const v = mapGet(map, key);
  if (v === null || v === undefined) return false;
  return String(v) === "true";
}