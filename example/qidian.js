// ==LSPosedScript==
// @name         七点工具箱日志 Hook
// @id           qidian.toolbox.log
// @version      2.1.0
// @author       XiaoHeiHook
// @description  在目标 Application.attach 中取得应用 ClassLoader，再 Hook pc.a.d；仅输出日志，不修改逻辑。
// @target       cn.am7code.tools
// @process      cn.am7code.tools
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// @grant        xposed.raw
// ==/LSPosedScript==

const TAG = "XHH-Qidian";
let pcHookInstalled = false;

xposed.onPackageLoaded(function (param) {
  console.log("七点工具箱脚本加载成功!", "package=", param.getPackageName(), "process=", env.processName);

  const packageName = String(param.getPackageName());
  if (packageName !== "cn.am7code.tools") {
    xposed.w(TAG, "skip unexpected package=" + packageName);
    return;
  }

  xposed.i(TAG, "onPackageLoaded: " + packageName);
  xposed.i(TAG, "process=" + env.processName);
  xposed.i(TAG, "raw param=" + param.raw);

  const Application = Java.type("android.app.Application");
  const ContextClass = Java.type("android.content.Context");

  // 关键点：目标业务类 pc.a 必须通过目标应用自己的 ClassLoader 加载。
  // 这里 Hook Application.attach(Context)，从 Context.getClassLoader() 取得真正的应用 ClassLoader，
  // 然后再安装 pc.a.d() 的 Hook。
  const attach = Application.getDeclaredMethod("attach", ContextClass);
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
        xposed.d(TAG, "Application.attach after, appClassLoader=" + loader);
        installPcHook(loader);
      } catch (e) {
        xposed.e(TAG, "install pc.a.d hook from Application.attach failed", e);
      }

      return result;
    });

  // Application.onCreate 只用于确认应用启动流程。
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

function installPcHook(loader) {
  if (pcHookInstalled) {
    xposed.d(TAG, "pc.a.d hook already installed, skip");
    return;
  }

  const TargetClass = loader.loadClass("pc.a");
  const targetMethod = TargetClass.getDeclaredMethod("d");
  targetMethod.setAccessible(true);

  xposed
    .hook(targetMethod)
    .setPriority(xposed.PRIORITY_DEFAULT)
    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
    .intercept(function (chain) {
      xposed.d(TAG, "pc.a.d before");
      xposed.d(TAG, "executable=" + chain.getExecutable());
      xposed.d(TAG, "thisObject=" + chain.getThisObject());
      xposed.d(TAG, "raw chain=" + chain.raw);

      const oldResult = chain.proceed();

      xposed.d(TAG, "pc.a.d after, result=" + oldResult);

      // const ret = debuggerx.breakpoint("测试断点", {
      //   packageName: env.packageName,
      //   processName: env.processName,
      //   scriptName: env.scriptName
      // });

      // if (ret !== null && ret !== undefined) {
      //   console.log("设置用户给定的返回值", ret);
      //   return ret
      // }

      // 不修改返回值，保持原逻辑。
      return true;  // 始终为 True
    });

  pcHookInstalled = true;
  xposed.i(TAG, "Hook installed with target app ClassLoader: pc.a.d");
}
