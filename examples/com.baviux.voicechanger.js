// ==LSPosedScript==
// @name         特效变音魔术师去广告
// @id           com.baviux.voicechanger
// @version      1.0.0
// @author       XiaoHeiHook
// @description  获得 VIP
// @target       com.baviux.voicechanger
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// ==/LSPosedScript==

const TAG = "com.baviux.voicechanger";

xposed.onPackageLoaded(function (param) {

    const TargetClass = param.getClassLoader().loadClass("q2.q");
    const method = TargetClass.getDeclaredMethod("b", "android.content.Context");
    method.setAccessible(true);

    xposed
      .hook(method)
      .setPriority(xposed.PRIORITY_DEFAULT)
      .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
      .intercept(function (chain) {

        try {
              oldResult = chain.proceed();
              xposed.d(TAG, "Original result=" + oldResult);
        } catch (e) {
              xposed.e(TAG, "Original call failed", e);
        }

        return true;
      });
      
});