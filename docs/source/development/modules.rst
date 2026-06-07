模块功能说明
====================

本页说明 XiaoHeiHook 中主要模块的职责。开发时可以先根据问题归类，再进入对应模块排查。

整体结构
--------------------

XiaoHeiHook 可以拆成四个执行区域：

.. code-block:: text

   手机管理端主进程
      ├─ 应用列表、脚本列表、设置页面、日志页面
      ├─ 脚本仓库与 Remote Preferences 操作
      └─ WebIDE Bridge Provider

   WebIDE :webide 进程
      ├─ 前台服务
      ├─ 本地 HTTP Server
      └─ React + Monaco 静态页面托管

   LSPosed / libxposed 目标进程
      ├─ HookEntry
      ├─ JsHookRuntime
      └─ 用户 JS 脚本

   公共数据通道
      ├─ Remote Files
      ├─ Remote Preferences
      ├─ 日志广播
      └─ 调试事件广播

核心模块
--------------------

.. list-table:: 模块职责
   :header-rows: 1
   :widths: 30 70

   * - 模块
     - 功能说明
   * - ``HookEntry.java``
     - LSPosed/libxposed 模块入口。接收 ``onModuleLoaded``、``onPackageLoaded``、``onPackageReady`` 等生命周期，读取 Remote Preferences 中的脚本索引，并决定当前包名、进程、运行时机下哪些脚本可以执行。
   * - ``script/JsHookRuntime.java``
     - Rhino JavaScript 运行时。负责创建 JS 上下文，注入 ``xposed``、``Java``、``env``、``settings``、``console``、``debuggerx`` 等对象，并把 JS 调用转接到 libxposed。
   * - ``data/ScriptRepository.kt``
     - 脚本仓库。负责扫描 ``Documents/XiaoHeiHook``，解析单文件脚本、多文件脚本、metadata、``settings.json``，并把脚本同步到 LSPosed Remote Files / Remote Preferences。
   * - ``data/ScriptModels.kt``
     - 脚本与同步数据模型。定义脚本 ID、路径、远程文件名、目标包名、运行时机、同步索引 key 等常量。
   * - ``data/ScriptSettings.kt``
     - 脚本设置系统。解析 ``settings.json`` schema，校验用户输入，合并默认值与应用级用户值，并生成运行时可读取的 settings。
   * - ``data/AppRepository.kt``
     - 应用列表仓库。读取安装应用、应用名、包名、图标、系统应用标记等信息。
   * - ``data/AppControl.kt``
     - 应用控制能力。用于启动、停止、重启目标应用，配合同步后验证脚本效果。
   * - ``data/LogReceiver.kt``
     - 管理端日志接收器。接收目标进程发回的脚本日志。
   * - ``data/AppLogRepository.kt``
     - 日志仓库。按应用保存日志文件，并为手机日志页、WebIDE 终端和系统文件编辑器打开日志提供数据。
   * - ``debug/DebugProtocol.kt``
     - 调试协议模型。定义断点、暂停、继续、变量、调用栈等事件结构。
   * - ``debug/JsDebugTrace.java``
     - JS 调试辅助。配合 ``debuggerx`` 生成调试事件。
   * - ``webide/WebIdeForegroundService.kt``
     - WebIDE 前台服务。负责通知栏、启动/停止 HTTP 服务、持有 WakeLock / WifiLock，并在关闭后退出 ``:webide`` 进程。
   * - ``webide/WebIdeServer.kt``
     - WebIDE HTTP 服务。监听端口，处理静态资源、API、日志流等请求。
   * - ``webide/WebIdeApi.kt``
     - WebIDE API 实现。提供应用列表、脚本读写、Hook 设置、同步、重启、日志读取、脚本设置保存等接口。
   * - ``webide/WebIdeBridgeProvider.kt``
     - 主进程桥接入口。由于 WebIDE 运行在独立进程，需要通过 Provider 回到主进程访问脚本仓库和 Remote Preferences。
   * - ``webide/WebIdeBridgeClient.kt``
     - ``:webide`` 进程访问主进程桥接接口的客户端封装。
   * - ``webide/SafeScriptPath.kt``
     - WebIDE 脚本路径安全校验，防止通过 API 访问脚本目录外文件。
   * - ``webide-src``
     - React + Monaco 前端源码。构建后输出到 ``app/src/main/assets/webide``。
   * - ``pages/AppsScreen.kt``
     - 手机端核心界面，包括应用列表、应用详情、脚本开关、脚本设置、日志查看等页面。
   * - ``composables/WebIdeSettingsCard.kt``
     - 手机端 WebIDE 开关、端口、绑定地址、电池优化提示等设置组件。

开发建议
--------------------

- 脚本扫描、同步、索引问题优先看 ``ScriptRepository``。
- 目标进程不执行脚本时，优先看 ``HookEntry`` 的筛选条件。
- JS API 行为异常时，优先看 ``JsHookRuntime``。
- WebIDE 页面访问异常时，先看 ``WebIdeForegroundService`` 和 ``WebIdeServer``。
- WebIDE 状态与手机端不一致时，优先看 Bridge Provider / Client。
