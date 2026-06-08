权限说明
====================

XiaoHeiHook 需要同时承担脚本管理、WebIDE 服务、日志查看、文件打开和目标 App 信息展示等功能，因此会使用多类 Android 权限。本页说明这些权限的用途和必要性。

应用列表相关权限
--------------------

.. list-table:: 应用查询权限
   :header-rows: 1
   :widths: 38 62

   * - 权限
     - 用途
   * - ``android.permission.QUERY_ALL_PACKAGES``
     - 用于读取设备上的应用列表，展示可配置 Hook 的目标应用。没有该权限时，新版 Android 只能看到有限应用，应用选择功能会不完整。

脚本文件相关权限
--------------------

.. list-table:: 文件权限
   :header-rows: 1
   :widths: 38 62

   * - 权限
     - 用途
   * - ``READ_EXTERNAL_STORAGE``
     - Android 12 及以下读取公共脚本目录时使用。
   * - ``WRITE_EXTERNAL_STORAGE``
     - Android 9 及以下写入公共脚本目录时使用。
   * - ``MANAGE_EXTERNAL_STORAGE``
     - Android 11 及以上访问 ``Documents/XiaoHeiHook`` 脚本目录时使用，便于扫描、编辑、保存多文件脚本。

脚本目录通常位于：

.. code-block:: text

   Documents/XiaoHeiHook/

如果没有足够的文件访问权限，管理端可能无法扫描脚本、WebIDE 可能无法保存脚本、多文件脚本依赖也可能无法完整读取。

WebIDE 网络相关权限
--------------------

.. list-table:: 网络权限
   :header-rows: 1
   :widths: 38 62

   * - 权限
     - 用途
   * - ``INTERNET``
     - 用于启动本地 HTTP WebIDE 服务，浏览器通过端口访问手机端页面和 API。即使只绑定 ``127.0.0.1``，Android 仍需要网络权限。
   * - ``ACCESS_WIFI_STATE``
     - 用于 WebIDE 保持 Wi-Fi 状态相关判断。
   * - ``CHANGE_WIFI_STATE``
     - 用于持有或调整 Wi-Fi 相关锁，减少 WebIDE 后台访问中断。

WebIDE 默认建议通过 adb 转发访问：

.. code-block:: bash

   adb forward tcp:8787 tcp:8787

MCP 远程调用默认建议通过 adb 转发访问：

.. code-block:: bash

   adb forward tcp:18787 tcp:18787

.. note::
   MCP 外部入口是用户手动开启的本地 HTTP 服务；目标 App 与 XiaoHeiHook 之间的内部 bridge 使用广播发现和动态 TCP 端口，不需要用户手动配置内部端口。

如果绑定局域网地址，需要明确提示用户存在安全风险。

前台服务与后台运行权限
----------------------------

.. list-table:: 前台服务权限
   :header-rows: 1
   :widths: 42 58

   * - 权限
     - 用途
   * - ``FOREGROUND_SERVICE``
     - WebIDE 在后台保持 HTTP 服务时需要以前台服务方式运行。
   * - ``FOREGROUND_SERVICE_SPECIAL_USE``
     - Android 14+ 对特殊用途前台服务的声明要求。WebIDE 属于用户主动开启的本地开发服务。
   * - ``FOREGROUND_SERVICE_DATA_SYNC``
     - 用于声明 WebIDE 涉及脚本文件同步、数据传输等前台服务类型。
   * - ``POST_NOTIFICATIONS``
     - Android 13+ 显示 WebIDE 运行通知需要通知权限。通知中提供关闭 WebIDE 的入口。
   * - ``WAKE_LOCK``
     - WebIDE / MCP 运行时保持 CPU 不被快速休眠，减少电脑端访问无响应。
   * - ``REQUEST_IGNORE_BATTERY_OPTIMIZATIONS``
     - 引导用户将 XiaoHeiHook 加入电池优化白名单，避免部分系统冻结 WebIDE 后台服务。

.. important::

   WebIDE 和 MCP 都是用户主动开启的开发服务。关闭服务时应停止前台服务、释放锁、移除通知，并退出对应的 ``:webide`` 或 ``:mcp`` 进程，避免无意义常驻。

FileProvider 与日志打开
----------------------------

手机端日志页支持“用其他应用打开日志”。这不是通过直接暴露文件路径实现，而是通过 ``FileProvider`` 临时授权：

.. code-block:: text

   top.lovepikachu.XiaoHeiHook.scriptfileprovider

这样外部文本编辑器可以读取日志文件，但无法访问 XiaoHeiHook 的其他私有文件。

Receiver 与跨进程事件
----------------------------

项目中还声明了日志和调试事件接收器：

.. code-block:: text

   top.lovepikachu.XiaoHeiHook.LOG_EVENT
   top.lovepikachu.XiaoHeiHook.DEBUG_EVENT

它们用于接收目标 App 进程中脚本运行时发回的日志和调试事件。

权限申请原则
--------------------

开发或修改权限时应遵循以下原则：

- 能只在用户主动开启 WebIDE 时使用的能力，不要常驻开启。
- 绑定 ``0.0.0.0`` 或局域网地址时必须给出风险提示。
- 文件访问权限只用于脚本目录、日志文件和用户明确选择的操作。
- 通知权限只用于 WebIDE 前台服务状态展示。
- 对 LSPosed Remote Preferences / Remote Files 的访问应只保存脚本同步、开关和配置相关数据。
