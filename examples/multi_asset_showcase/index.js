// ==LSPosedScript==
// @name         多文件资源复制与展示示例
// @id           demo.multi.asset.showcase
// @version      1.0.0
// @author       XiaoHeiHook
// @description  应用启动时复制 assets 资源到目标 App 私有目录，并尝试用 Activity Dialog 展示图片。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// ==/LSPosedScript==

require("./main.js").install();
