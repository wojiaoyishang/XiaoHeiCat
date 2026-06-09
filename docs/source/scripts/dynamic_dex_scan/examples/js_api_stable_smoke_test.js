// XiaoHeiHook 1.30 (106) cross-version JS API stable-return smoke test
// 目的：覆盖保留到 JS 层的主要接口，确认复杂返回值均可用 obj.field / arr[i] / arr.length 访问，
// 不再依赖 Java List<Map>.get(...).get(...)。
//
// 使用方式：按目标 App 修改 TARGET_CLASS / TARGET_METHOD / 特征后运行。
// 某些 dump/scan/remote file 接口依赖 grant、设备权限和运行时机；失败时只记录错误，不中断。

// @grant xhh
// @grant mcp.register
// @grant dex.read
// @grant dex.search
// @grant dex.dump
// @grant dex.debug
// @grant remote.files
// @grant remote.preferences

(function () {
  const TAG = "XHH-CrossApiSmoke";
  const TARGET_CLASS = "com.example.Target";
  const TARGET_METHOD = "isVip";
  const TARGET_PROTO = "()Z";
  const STRING_FEATURES = ["access_token"];
  const SMALI_FEATURES = ["Ljava/lang/System;->currentTimeMillis("];

  function json(value) {
    try { return JSON.stringify(value); }
    catch (_) { return String(value); }
  }

  function log(label, value) {
    xposed.i(TAG, label + " = " + json(value));
  }

  function safe(label, fn, fallback) {
    try {
      const ret = fn();
      log(label, ret);
      return ret;
    } catch (e) {
      xposed.e(TAG, label + " failed", e);
      return fallback;
    }
  }

  function pathsOf(ret) {
    if (!ret) return [];
    return ret.paths || ret.dumpedPaths || [];
  }

  // xhh / settings / env / console / Java
  const info = safe("xhh.info", function () { return xhh.info(); }, {});
  safe("xhh.hasGrant.dex.dump", function () { return { ok: true, granted: xhh.hasGrant("dex.dump") }; }, {});
  safe("xhh.objectKind", function () {
    return {
      jsObject: xhh.objectKind({ hello: "world" }),
      jsArray: xhh.objectKind([1, 2, 3]),
      javaClass: xhh.objectKind(Java.type("java.lang.String")),
      isJsObject: xhh.isJsObject({}),
      isJavaObject: xhh.isJavaObject(Java.type("java.lang.String"))
    };
  }, {});
  safe("settings.all", function () { return settings.all(); }, {});
  safe("settings.get", function () { return { ok: true, value: settings.get("sample", { fallback: true }) }; }, {});
  safe("env", function () {
    return {
      packageName: env.packageName,
      processName: env.processName,
      scriptName: env.scriptName,
      isMainProcess: env.processName === env.packageName
    };
  }, {});
  safe("console", function () {
    console.log("console.log smoke");
    console.warn("console.warn smoke");
    console.error("console.error smoke");
    return { ok: true };
  }, {});
  safe("Java.type", function () {
    const StringClass = Java.type("java.lang.String");
    return { ok: true, name: StringClass.getName() };
  }, {});

  // xposed facade
  safe("xposed.stackTrace", function () {
    const st = xposed.stackTrace({ maxDepth: 4 });
    return { ok: true, length: st.length, first: st.length > 0 ? st[0] : null };
  }, {});
  safe("xposed.getJavaStackTrace", function () {
    const st = xposed.getJavaStackTrace();
    return { ok: true, length: st.length, first: st.length > 0 ? st[0] : null };
  }, {});
  safe("xposed.getAppStackTrace", function () {
    const st = xposed.getAppStackTrace();
    return { ok: true, length: st.length, first: st.length > 0 ? st[0] : null };
  }, {});
  safe("xposed.e.thirdArgString", function () {
    xposed.e(TAG, "third argument accepts non-Throwable", "plain-js-error-text");
    return { ok: true };
  }, {});
  safe("xposed.e.thirdArgObject", function () {
    xposed.e(TAG, "third argument accepts object", { code: "JS_OBJECT" });
    return { ok: true };
  }, {});
  safe("xposed.listRemoteFiles", function () {
    const files = xposed.listRemoteFiles(".");
    return { ok: true, length: files.length, first: files.length > 0 ? files[0] : null };
  }, {});
  safe("xposed.getFrameworkProperties", function () { return xposed.getFrameworkProperties(); }, {});
  safe("xposed.getModuleApplicationInfo", function () { return xposed.getModuleApplicationInfo(); }, {});

  // RPC / MCP registration facade
  safe("xhh.rpc.register_method", function () {
    return xhh.rpc.register_method("xhh_cross_api_echo", {
      description: "Echo method for XiaoHeiHook 1.30 cross-version smoke test",
      timeoutMs: 3000,
      conflict: "overwrite"
    }, function (params) {
      return { ok: true, echo: params };
    });
  }, {});
  safe("xhh.rpc.unregister_method", function () { return xhh.rpc.unregister_method("xhh_cross_api_echo"); }, {});
  safe("xhh.rpc.unregister_all_methods", function () { return xhh.rpc.unregister_all_methods(); }, {});

  // dex facade：只有声明并授予 dex grant 时才存在。
  if (typeof dex !== "undefined") {
    const limits = safe("dex.limits", function () { return dex.limits(); }, { limits: {} });
    safe("dex.setLimits", function () {
      return dex.setLimits({
        maxDexBytes: limits.limits && limits.limits.maxDexBytes || 256 * 1024 * 1024,
        maxMethods: limits.limits && limits.limits.maxMethods || 1000000,
        maxSmaliChars: limits.limits && limits.limits.maxSmaliChars || 500000
      });
    }, {});

    safe("dex.runtimeLoaders", function () { return dex.runtimeLoaders(); }, []);
    safe("dex.runtimeSources", function () { return dex.runtimeSources(); }, []);
    safe("dex.watchLoaders", function () { return { ok: dex.watchLoaders() === true }; }, {});

    const pkg = info.packageName || env.packageName || "unknown";
    const dumpDir = "/data/user/0/" + pkg + "/code_cache/xhh_dumpdex";

    const cookieDump = safe("dex.dumpDexCookies", function () {
      return dex.dumpDexCookies({
        outputDir: dumpDir,
        clearOutputDir: false,
        maxDumpCount: 16,
        maxDexBytes: 256 * 1024 * 1024,
        includeParents: true,
        includeThreadContext: true,
        registerSources: true
      });
    }, { paths: [] });

    const sourceList = safe("dex.dumpSources.diagnosticOnly", function () {
      return dex.dumpSources({ dir: dumpDir, limit: 32 });
    }, []);

    let paths = pathsOf(cookieDump);
    if (paths.length === 0 && sourceList && sourceList.length > 0) {
      // dumpSources 仅用于诊断展示。控制流仍应优先使用 dump 接口直接返回的 paths。
      paths = sourceList.map(function (item) { return item.path; }).filter(function (p) { return !!p; });
    }

    let firstHit = null;
    xhh.each(paths, function (p, i) {
      const byString = safe("dex.findMethods.byString[" + i + "]", function () {
        return dex.findMethods({ path: p, strings: STRING_FEATURES, limit: 5 });
      }, []);
      const byProto = safe("dex.findMethods.byProto[" + i + "]", function () {
        return dex.findMethods({ path: p, proto: TARGET_PROTO, limit: 5 });
      }, []);
      const bySmali = safe("dex.findMethods.bySmali[" + i + "]", function () {
        return dex.findMethods({ path: p, smaliContains: SMALI_FEATURES, limit: 5 });
      }, []);
      const hit = (bySmali && bySmali[0]) || (byString && byString[0]) || (byProto && byProto[0]);
      if (!hit) return true;
      firstHit = hit;
      log("firstHit", { path: p, className: hit.className, methodName: hit.methodName, proto: hit.proto });
      return false;
    });

    safe("dex.findMethod", function () {
      if (paths.length === 0) return null;
      return dex.findMethod({ path: paths[0], proto: TARGET_PROTO, limit: 1 });
    }, null);

    safe("dex.inspectMethodInFile", function () {
      if (!firstHit) return { ok: false, skipped: true, reason: "no-findMethods-hit" };
      return dex.inspectMethodInFile({
        path: firstHit.path || firstHit.sourcePath,
        className: firstHit.className,
        methodName: firstHit.methodName,
        proto: firstHit.proto,
        strings: STRING_FEATURES,
        smaliChars: 4000
      });
    }, {});

    safe("dex.locateMethodInCookieDumps", function () {
      return dex.locateMethodInCookieDumps({
        dir: dumpDir,
        prefix: "cookie_",
        className: firstHit ? firstHit.className : TARGET_CLASS,
        methodName: firstHit ? firstHit.methodName : TARGET_METHOD,
        proto: firstHit ? firstHit.proto : TARGET_PROTO,
        maxFiles: 32
      });
    }, {});

    safe("dex.dumpClassDex", function () { return dex.dumpClassDex({ className: TARGET_CLASS }); }, { paths: [] });
    safe("dex.dumpLoadedClassDex", function () {
      const c = Java.type(TARGET_CLASS);
      return dex.dumpLoadedClassDex(c);
    }, { paths: [] });

    safe("dex.classLoadDumpStatus", function () { return dex.classLoadDumpStatus(); }, {});
    safe("dex.classDexDumpStatus", function () { return dex.classDexDumpStatus(); }, {});
    safe("dex.classDexDumpSources", function () { return dex.classDexDumpSources(); }, []);
    safe("dex.classDumpDirStatus", function () { return dex.classDumpDirStatus(); }, {});
    safe("dex.dumpSources", function () { return dex.dumpSources({ dir: dumpDir }); }, []);
    safe("dex.memorySources", function () { return dex.memorySources(); }, []);
    safe("dex.scanMemory", function () { return dex.scanMemory({ outputDir: dumpDir + "_memory", maxDumpCount: 3, registerSources: false }); }, { paths: [] });
    safe("dex.dumpMemory", function () { return dex.dumpMemory({ outputDir: dumpDir + "_memory_dump", maxDumpCount: 3 }); }, { paths: [] });
    safe("dex.dumpMemoryRaw", function () { return dex.dumpMemoryRaw({ outputDir: dumpDir + "_memory_raw", maxDumpCount: 3 }); }, { paths: [] });
  }

  xposed.i(TAG, "cross-version stable-return smoke test done");
})();
