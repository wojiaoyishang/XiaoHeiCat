// ==LSPosedScript==
// @name         App 启动后 Toast
// @id           demo.toast.after.launch
// @version      1.0.0
// @description  在 Application.attach 之后弹出 Toast。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// ==/LSPosedScript==

const TAG = "ToastDemo";

let executed = false;

function showToast(context, text) {
  const Toast = Java.use("android.widget.Toast");
  const Looper = Java.use("android.os.Looper");
  const Handler = Java.use("android.os.Handler");

  const mainHandler = new Handler(Looper.getMainLooper());

  mainHandler.post(function () {
    Toast.makeText(context, String(text), Toast.LENGTH_SHORT).show();
  });
}


xposed.onPackageLoaded(function (param) {
  const packageName = String(param.getPackageName());
  const processName = String(env.processName || "");

  xposed.i(TAG, "loaded package=" + packageName + " process=" + processName);

  const Application = Java.use("android.app.Application");

  const attach = Application.getDeclaredMethod("attach", "android.content.Context");
  attach.setAccessible(true);

  xposed
    .hook(attach)
    .setPriority(xposed.PRIORITY_DEFAULT)
    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
    .intercept(function (chain) {
      const context = chain.getArg(0);
      const result = chain.proceed();

      try {
        if (executed) {
          return result;
        }

        executed = true;

        const appPackage = String(context.getPackageName());

        xposed.i(TAG, "Application.attach after package=" + appPackage);

        showToast(context, "XiaoHeiHook 脚本已运行: " + appPackage);
      } catch (e) {
        xposed.e(TAG, "show toast failed", e);
      }

      return result;
    });
});