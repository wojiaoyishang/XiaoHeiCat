// ==LSPosedScript==
// @name         Java 调用栈查看示例
// @id           demo.stack.trace
// @version      1.0.0
// @author       XiaoHeiHook
// @description  演示 xposed.printAppStackTrace / getAppStackTrace / stackTrace，用于授权调试。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// @grant        xposed.raw
// ==/LSPosedScript==

const TAG = "XHH-StackDemo"

xposed.onPackageLoaded(function (param) {
  const packageName = String(param.getPackageName())
  xposed.i(TAG, "loaded package=" + packageName + ", process=" + env.processName)

  const Application = Java.use("android.app.Application")
  const attach = Application.getDeclaredMethod("attach", "android.content.Context")
  attach.setAccessible(true)

  xposed
    .hook(attach)
    .setPriority(xposed.PRIORITY_DEFAULT)
    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
    .intercept(function (chain) {
      const context = chain.getArg(0)

      xposed.i(TAG, "Application.attach before context=" + context)

      // 推荐：打印过滤后的目标应用调用栈。
      xposed.printAppStackTrace(TAG, "Application.attach before")

      // 如果需要完整栈，包括 Rhino/XiaoHeiHook 内部帧，可以使用：
      // xposed.printJavaStackTrace(TAG, "Application.attach full stack")

      const result = chain.proceed()

      // 获取字符串形式，自己决定如何输出。
      const appStackText = xposed.getAppStackTraceString()
      xposed.d(TAG, "App stack after attach:\n" + appStackText)

      // 获取结构化栈帧，只输出前 8 帧。
      const frames = xposed.stackTrace({
        appOnly: true,
        maxDepth: 8,
        skipNative: false
      })

      for (let i = 0; i < frames.length; i++) {
        const f = frames[i]
        xposed.d(TAG, "frame#" + i + " " + f.className + "." + f.methodName + "(" + f.fileName + ":" + f.lineNumber + ")")
      }

      return result
    })
})
