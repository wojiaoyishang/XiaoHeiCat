const assets = require("./lib/assets.js");
const showcase = require("./lib/showcase.js");

const TAG = "XHH-MultiAssetDemo";
let executed = false;

function install() {
  xposed.onPackageLoaded(function (param) {
    xposed.i(TAG, "loaded package=" + param.getPackageName() + ", process=" + env.processName);

    const Application = Java.use("android.app.Application");

    const attach = Application.getDeclaredMethod("attach", "android.content.Context");
    attach.setAccessible(true);

    xposed.hook(attach).intercept(function (chain) {
      const context = chain.getArg(0);
      const result = chain.proceed();

      if (executed) return result;
      executed = true;

      try {
        const prepared = assets.prepare(context, TAG);

        xposed.i(TAG, "scriptRoot=" + xhh.fs.scriptRoot());
        xposed.i(TAG, "scriptDir=" + xhh.fs.scriptDir());
        xposed.i(TAG, "assetsDir=" + xhh.fs.assetsDir());
        xposed.i(TAG, "targetDir=" + prepared.targetDir);
        xposed.i(TAG, "image copied path=" + prepared.image.path);
        xposed.i(TAG, "html copied path=" + prepared.html.path);
        xposed.i(TAG, "html url=file://" + prepared.html.path);

        showcase.installActivityDialog(prepared, TAG);
      } catch (e) {
        xposed.e(TAG, "prepare/showcase failed", e);
      }

      return result;
    });
  });
}

module.exports = {
  install: install
};
