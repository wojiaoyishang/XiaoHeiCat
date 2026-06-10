// ==LSPosedScript==
// @name         七点工具箱 smali 特征定位
// @id           qidian.locate.smali.features.foreach
// @version      1.2.0
// @description  先 dump 已脱壳 dex，再使用 dex.forEachMethod 遍历所有方法，通过 smali 字符串特征定位目标方法。
// @target       cn.am7code.tools
// @process      cn.am7code.tools
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// @grant        dex.dump
// @grant        dex.full
// ==/LSPosedScript==

var TAG = "QidianSmaliFind";

var PACKAGE_NAME = "cn.am7code.tools";

var FEATURES = [
  "am7_dev_vip_override",
  "getString",
  "vip",
  "nonvip"
];

var INVOKE_CONTAINS = [
  "Landroid/content/SharedPreferences;->getString(",
  "Lwork/am7code/common/userinfo/model/Am7UserInfo;->getVip_start_time(",
  "Lwork/am7code/common/userinfo/model/Am7UserInfo;->getVip_duration(",
  "Ljava/lang/System;->currentTimeMillis("
];

var executed = false;

xposed.onPackageLoaded(function (param) {
  var pkg = String(param.getPackageName());
  if (pkg !== PACKAGE_NAME) return;

  xposed.i(TAG, "script loaded package=" + pkg + " process=" + env.processName);

  var Application = Java.use("android.app.Application");
  var attach = Application.getDeclaredMethod("attach", "android.content.Context");
  attach.setAccessible(true);

  xposed
    .hook(attach)
    .setPriority(xposed.PRIORITY_DEFAULT)
    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
    .intercept(function (chain) {
      var context = chain.getArg(0);
      var result = chain.proceed();

      if (executed) return result;
      executed = true;

      try {
        run(context);
      } catch (e) {
        xposed.e(TAG, "search failed", e);
      }

      return result;
    });
});

function run(context) {
  var loader = context.getClassLoader();
  var dumpDir = "/data/user/0/" + PACKAGE_NAME + "/code_cache/xhh_dumpdex";

  xposed.i(TAG, "appClassLoader=" + loader);
  xposed.i(TAG, "dumpDir=" + dumpDir);

  var dump = dex.dumpDexCookies({
    loader: loader,
    outputDir: dumpDir,
    maxDexBytes: 512 * 1024 * 1024,
    maxDumpCount: 256,
    includeParents: false,
    includeThreadContext: false,
    registerSources: true,
    verbose: true
  });

  var paths = dump.paths || dump.dumpedPaths;

  xposed.i(TAG, "dump count=" + dump.count);
  xposed.i(TAG, "dump paths count=" + paths.length);

  if (!paths || paths.length <= 0) {
    xposed.w(TAG, "no dumped dex paths");
    return;
  }

  var found = null;
  var totalVisitedClasses = 0;
  var totalVisitedMethods = 0;

  for (var i = 0; i < paths.length; i++) {
    var dexPath = String(paths[i]);

    xposed.i(TAG, "========== searching dex [" + (i + 1) + "/" + paths.length + "] ==========");
    xposed.i(TAG, "current dex=" + dexPath);

    var ret = dex.forEachMethod({
      path: dexPath,

      includeSmali: true,
      includeStrings: true,
      includeInvokes: true,
      maxSmaliChars: 160000,

      limit: 0,
      stopOnCallbackError: false,
      verbose: false
    }, function (m) {
      var smali = text(m.smali);
      var strings = text(m.strings);
      var invokes = text(m.invokes);

      var allText = smali + "\n" + strings + "\n" + invokes;

      var missingFeatures = missingInText(allText, FEATURES);
      var missingInvokes = missingInText(allText, INVOKE_CONTAINS);

      var featureHitCount = FEATURES.length - missingFeatures.length;
      var invokeHitCount = INVOKE_CONTAINS.length - missingInvokes.length;
      var score = featureHitCount + invokeHitCount;

      if (score > 0) {
        xposed.i(
          TAG,
          "candidate score=" + score +
            " class=" + text(m.className) +
            " method=" + text(m.methodName) +
            " descriptor=" + text(m.descriptor) +
            " path=" + text(m.path)
        );
      }

      if (missingFeatures.length === 0 && missingInvokes.length === 0) {
        found = {
          className: text(m.className),
          methodName: text(m.methodName),
          descriptor: text(m.descriptor),
          path: text(m.path),
          sourcePath: text(m.sourcePath),
          isStatic: boolAny(m.static) || boolAny(m.isStatic),
          score: score,
          featuresOk: true,
          invokesOk: true,
          missingFeatures: missingFeatures,
          missingInvokes: missingInvokes,
          smaliHead: smali
        };

        return "stop";
      }

      return false;
    });

    totalVisitedClasses += numberValue(ret.visitedClasses);
    totalVisitedMethods += numberValue(ret.visitedMethods);

    xposed.i(
      TAG,
      "dex done visitedClasses=" + ret.visitedClasses +
        " visitedMethods=" + ret.visitedMethods +
        " stopped=" + ret.stopped
    );

    if (found) {
      break;
    }
  }

  xposed.i(TAG, "totalVisitedClasses=" + totalVisitedClasses);
  xposed.i(TAG, "totalVisitedMethods=" + totalVisitedMethods);

  if (!found) {
    xposed.w(TAG, "target method not found by smali features");
    return;
  }

  xposed.i(TAG, "========== FOUND ==========");
  xposed.i(TAG, "path=" + found.path);
  xposed.i(TAG, "sourcePath=" + found.sourcePath);
  xposed.i(TAG, "className=" + found.className);
  xposed.i(TAG, "methodName=" + found.methodName);
  xposed.i(TAG, "descriptor=" + found.descriptor);
  xposed.i(TAG, "static=" + found.isStatic);
  xposed.i(TAG, "score=" + found.score);
  xposed.i(TAG, "featuresOk=" + found.featuresOk);
  xposed.i(TAG, "invokesOk=" + found.invokesOk);
  xposed.i(TAG, "smali.head=" + found.smaliHead);
}

function missingInText(textValue, keywords) {
  var missing = [];
  var i;

  for (i = 0; i < keywords.length; i++) {
    var k = String(keywords[i]);

    if (textValue.indexOf(k) < 0) {
      missing.push(k);
    }
  }

  return missing;
}

function text(v) {
  if (v === null || v === undefined) return "";
  return String(v);
}

function boolAny(v) {
  if (v === true) return true;
  if (v === false) return false;
  return String(v) === "true";
}

function numberValue(v) {
  if (v === null || v === undefined) return 0;

  try {
    return Number(v);
  } catch (e) {
    return 0;
  }
}