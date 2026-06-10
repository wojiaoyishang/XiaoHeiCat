// ==LSPosedScript==
// @name         Hook OkHttp 多文件示例
// @id           demo.okhttp.multifile.all.packages
// @version      1.32.109
// @author       XiaoHeiHook
// @description  XiaoHeiHook 1.32 (109) 多文件 OkHttp Hook 示例，默认匹配所有包名，仅用于授权调试。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// @grant        xposed.raw
// ==/LSPosedScript==

var config = require("./config.js")
var logger = require("./logger.js")
var okhttp = require("./okhttp.js")

xposed.onPackageLoaded(function (param) {
  var packageName = String(param.getPackageName())
  var processName = String(env.processName)

  if (!config.shouldHookPackage(packageName, processName)) {
    logger.debug("skip package=" + packageName + ", process=" + processName)
    return
  }

  logger.info("OkHttp multi-file hook loaded")
  logger.info("package=" + packageName + ", process=" + processName)
  logger.info("scriptPath=" + env.scriptPath)

  okhttp.install(param, config, logger)
})
