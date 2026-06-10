// ==LSPosedScript==
// @name         nh
// @id           nh
// @version      1.0.0
// @author       XiaoHeiHook
// @description  南方航空解密 MCP 调用示例
// @target       com.csair.mbp
// @process      com.csair.mbp
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// @grant        dex.full
// @grant        rpc.register
// ==/LSPosedScript==

const TAG = "NH";

let pcHookInstalled = false;
let appClassLoader = null;

const DECRYPT_META = {
  className: "com.ijiami.whitebox.Sm4EncryptBox",
  methodName: "decryptFromBase64_CBC",
  paramTypes: [
    "java.lang.String",
    "java.lang.String",
    "java.lang.String",
    "int"
  ],
  isStatic: true
};

function resolveDecryptMethod() {
  let TargetClass;

  if (appClassLoader != null) {
    TargetClass = appClassLoader.loadClass(DECRYPT_META.className);
  } else {
    TargetClass = Java.use(DECRYPT_META.className);
  }

  // 1.32 (109) 起，getDeclaredMethod 的签名参数支持字符串数组。
  const method = TargetClass.getDeclaredMethod(
    DECRYPT_META.methodName,
    DECRYPT_META.paramTypes
  );

  method.setAccessible(true);
  return method;
}

function installPcHook(loader) {
  if (pcHookInstalled) {
    xposed.d(TAG, "hook already installed, skip");
    return;
  }

  appClassLoader = loader;

  const TargetClass = loader.loadClass(DECRYPT_META.className);

  // 1.32 (109) 起，基础类型和常见 Java 类可以直接写字符串签名。
  const targetMethod = TargetClass.getDeclaredMethod(
    DECRYPT_META.methodName,
    DECRYPT_META.paramTypes
  );

  targetMethod.setAccessible(true);

  xposed
    .hook(targetMethod)
    .setPriority(xposed.PRIORITY_DEFAULT)
    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
    .intercept(function (chain) {
      const args = chain.getArgs();

      xposed.d(TAG, "decrypt method called");
      xposed.d(TAG, "executable=" + chain.getExecutable());
      xposed.d(TAG, "thisObject=" + chain.getThisObject());
      xposed.d(TAG, "arg0=" + args[0]);
      xposed.d(TAG, "arg1=" + args[1]);
      xposed.d(TAG, "arg2=" + args[2]);
      xposed.d(TAG, "arg3=" + args[3]);

      return chain.proceed();
    });

  pcHookInstalled = true;
  xposed.i(
    TAG,
    "Hook installed: " + DECRYPT_META.className + "." + DECRYPT_META.methodName
  );
}

xhh.rpc.register_method("decrypt_cbc", function (params) {
  try {
    if (appClassLoader == null) {
      return {
        ok: false,
        error: "appClassLoader not ready, please launch target app first",
        process: env.processName,
        meta: DECRYPT_META
      };
    }

    const method = resolveDecryptMethod();

    const data = params.data == null ? "" : String(params.data);
    const key = params.key == null ? "" : String(params.key);
    const iv = params.iv == null ? "" : String(params.iv);

    // 1.32 (109) 推荐写法：需要精确 Java 类型时使用 Java.to。
    const mode = Java.to("int", params.mode == null || params.mode === "" ? 0 : params.mode);

    const result = method.invoke(
      null,
      data,
      key,
      iv,
      mode
    );

    return {
      ok: true,
      result: result == null ? null : String(result),
      process: env.processName,
      meta: {
        className: DECRYPT_META.className,
        methodName: DECRYPT_META.methodName,
        isStatic: DECRYPT_META.isStatic
      }
    };
  } catch (e) {
    xposed.e(TAG, "decrypt_cbc failed", e);

    return {
      ok: false,
      error: String(e),
      process: env.processName,
      meta: DECRYPT_META
    };
  }
});

xhh.rpc.register_method("decrypt_cbc_status", function () {
  return {
    ok: true,
    process: env.processName,
    hookInstalled: pcHookInstalled,
    appClassLoaderReady: appClassLoader != null,
    meta: DECRYPT_META
  };
});

xposed.onPackageLoaded(function (param) {
  console.log(
    "南方航空脚本加载成功!",
    "package=",
    param.getPackageName(),
    "process=",
    env.processName
  );

  const packageName = String(param.getPackageName());

  xposed.i(TAG, "onPackageLoaded: " + packageName);
  xposed.i(TAG, "process=" + env.processName);
  xposed.i(TAG, "raw param=" + param.raw);

  const Application = Java.use("android.app.Application");

  // 1.32 (109) 起，getDeclaredMethod 可直接使用字符串签名。
  const attach = Application.getDeclaredMethod("attach", "android.content.Context");
  attach.setAccessible(true);

  xposed
    .hook(attach)
    .setPriority(xposed.PRIORITY_DEFAULT)
    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
    .intercept(function (chain) {
      const context = chain.getArg(0);

      xposed.d(TAG, "Application.attach before, context=" + context);

      const result = chain.proceed();

      try {
        const loader = context.getClassLoader();
        appClassLoader = loader;

        xposed.d(TAG, "Application.attach after, appClassLoader=" + loader);

        installPcHook(loader);
      } catch (e) {
        xposed.e(TAG, "install hook from Application.attach failed", e);
      }

      return result;
    });

  const onCreate = Application.getDeclaredMethod("onCreate");
  onCreate.setAccessible(true);

  xposed
    .hook(onCreate)
    .setPriority(xposed.PRIORITY_DEFAULT)
    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
    .intercept(function (chain) {
      xposed.d(TAG, "Application.onCreate before");

      const result = chain.proceed();

      xposed.d(TAG, "Application.onCreate after");

      return result;
    });
});