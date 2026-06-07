WebIDE 实现说明
====================

WebIDE 是运行在手机端的本地网页 IDE。电脑浏览器通过 ``adb forward`` 或局域网地址访问手机上的 HTTP 服务，从而完成脚本编写、保存、同步、重启应用和查看日志。

运行结构
--------------------

WebIDE 不运行在普通 Activity 线程中，而是运行在独立前台服务进程：

.. code-block:: text

   手机端设置页开启 WebIDE
      ↓
   WebIdeForegroundService 启动
      ↓
   :webide 独立进程
      ↓
   WebIdeServer 监听端口
      ↓
   浏览器访问 React + Monaco 页面

这样可以降低主界面切后台后 HTTP 服务被系统冻结的概率。

前台服务
--------------------

``WebIdeForegroundService`` 负责：

- 显示 WebIDE 运行中的通知。
- 启动和停止 ``WebIdeServer``。
- 持有必要的 WakeLock / WifiLock。
- 处理通知栏中的关闭按钮。
- 关闭后释放资源并退出 ``:webide`` 进程。

WebIDE 关闭时必须完整清理，避免后台残留服务继续监听端口。

独立进程与 Bridge
--------------------

WebIDE 服务运行在 ``:webide`` 进程，而应用列表、脚本仓库、Remote Preferences 操作主要在主进程中。两个进程不能直接共享普通内存对象，因此需要桥接层：

.. code-block:: text

   :webide 进程 WebIdeApi
      ↓ WebIdeBridgeClient
   主进程 WebIdeBridgeProvider
      ↓
   ScriptRepository / AppRepository / Remote Preferences

这种设计让 WebIDE 可以保持独立运行，同时复用主进程已有的脚本扫描、同步和开关逻辑。

静态资源托管
--------------------

WebIDE 前端源码位于：

.. code-block:: text

   webide-src/

使用 React + Monaco 编写。构建后输出到：

.. code-block:: text

   app/src/main/assets/webide/

``WebIdeServer`` 负责托管：

.. code-block:: text

   /
   /index.html
   /assets/*

由于 Monaco 会生成多个 chunk 和 worker 文件，静态资源路由必须支持 ``/assets/*``，不能只写死 ``app.js``。

主要 API
--------------------

WebIDE 后端提供以下常用接口：

.. code-block:: text

   GET  /api/status
   GET  /api/apps
   GET  /api/scripts
   GET  /api/scripts/read?path=...
   POST /api/scripts/save
   POST /api/scripts/create
   POST /api/scripts/delete
   POST /api/scripts/rename
   GET  /api/hook-settings?packageName=...
   POST /api/hook-settings/app-enabled
   POST /api/hook-settings/script-enabled
   POST /api/hook-settings/sync
   POST /api/apps/restart
   GET  /api/logs?packageName=...
   GET  /api/logs/stream?packageName=...

脚本文件 API 必须经过 ``SafeScriptPath`` 校验，防止通过路径穿越访问脚本目录外文件。

前端功能
--------------------

WebIDE 前端主要包括：

- 软件列表：选择目标应用。
- 全部脚本：管理和打开所有脚本。
- Monaco 编辑器：多标签编辑、JS 高亮、快捷键保存。
- Hook 设置面板：应用开关、脚本开关、同步、重启、脚本设置。
- 终端：实时显示目标 App 日志和操作结果。

保存与同步的关系
--------------------

WebIDE 中“保存”和“同步”是两个动作：

.. code-block:: text

   保存：写入 Documents/XiaoHeiHook 本地脚本文件
   同步：写入 LSPosed Remote Files / Remote Preferences

只保存不等于目标 App 已经使用新脚本。脚本修改后通常需要保存、同步，并根据运行时机重启目标 App。

安全策略
--------------------

默认建议绑定：

.. code-block:: text

   127.0.0.1

并通过 adb 转发访问：

.. code-block:: bash

   adb forward tcp:8787 tcp:8787

如果用户绑定 ``0.0.0.0`` 或局域网地址，必须提示这是高风险行为，因为同一网络中的其他设备可能访问 WebIDE。
