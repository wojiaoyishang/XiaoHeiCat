// ==LSPosedScript==
// @name         Hook OkHttp 示例
// @id           demo.okhttp.multifile.logger
// @version      1.0.0
// @author       XiaoHeiHook
// @description  多文件脚本示例：Hook OkHttp 请求/响应基础信息，仅用于授权调试。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// @grant        xposed.raw
// ==/LSPosedScript==

const config = require("./config.js")
const logger = require("./logger.js")
const okhttp = require("./okhttp.js")

xposed.onPackageLoaded(function (param) {
  const packageName = String(param.getPackageName())

  if (config.targetPackages.indexOf(packageName) < 0) {
    logger.warn("skip package=" + packageName)
    return
  }

  logger.info("OkHttp multi-file logger loaded")
  logger.info("package=" + packageName + ", process=" + env.processName)
  logger.info("scriptPath=" + env.scriptPath)

  okhttp.install(param, config, logger)
})
