MCP 远程调用
=============================

XiaoHeiHook 提供 MCP 远程调用能力。这个功能允许电脑端 MCP Client 调用目标 App 中由 JS 脚本主动注册的方法。

.. note::
	请确认 XiaoHeiHook 版本 >= 1.20 (102) 。

设计目标
-----------

MCP 外层只暴露两个稳定工具：

``list_methods``
    列出当前目标 App 进程已经注册的远程方法。

``invoke_method``
    调用某个 JS 已注册方法，并返回结构化 JSON 结果。

外部电脑端不能直接反射 Java 方法，不能任意读写字段，也不能执行任意 JS。远程可调用能力完全由目标 App 内的 JS 脚本通过 ``xhh.rpc.register_method`` 主动暴露。

启用方式
-----------

在 XiaoHeiHook 的设置页面中找到 **MCP 远程调用** 卡片，手动打开开关。每次 XiaoHeiHook 主应用重启后，MCP 默认保持关闭，需要用户重新开启。

默认监听地址：

::

   http://127.0.0.1:18787/mcp

电脑通过 ADB 转发访问：

::

   adb forward tcp:18787 tcp:18787

然后 MCP Client 连接：

::

   http://127.0.0.1:18787/mcp

访问令牌
-----------

MCP token 是可选的。

启用 token 后，电脑端请求必须携带：

::

   Authorization: Bearer <token>

不支持 query 参数 token，也不支持额外兜底 header。设置页中的 token 输入框支持左右拖动查看，长按可以复制。

JS 注册方法
--------------

最小示例：

.. code-block:: javascript

   // ==UserScript==
   // @name         MCP Echo Test
   // @target       *
   // @grant        rpc.register
   // ==/UserScript==

   xhh.rpc.register_method("echo", function (params, ctx) {
     return {
       ok: true,
       received: params,
       requestId: ctx.requestId,
       timestamp: Date.now()
     }
   })

带注册选项：

.. code-block:: javascript

   xhh.rpc.register_method("get_user_token", {
     description: "获取当前用户 token",
     conflict: "overwrite",
     timeoutMs: 5000,
     concurrency: "parallel",
     paramsSchema: {
       type: "object",
       properties: {
         forceRefresh: { type: "boolean" }
       }
     }
   }, async function (params, ctx) {
     return {
       token: "..."
     }
   })

重复注册策略
---------------

``conflict`` 支持三种取值：

``overwrite``
    默认值。已有同名方法时覆盖旧 handler。

``ignore``
    已有同名方法时忽略本次注册。

``error``
    已有同名方法时返回注册冲突错误。

作用域为：

::

   packageName + processName + methodName

MCP 工具参数
---------------

``list_methods`` 输入示例：

.. code-block:: json

   {
     "packageName": "cn.am7code.tools",
     "processName": "cn.am7code.tools",
     "includeSchema": true
   }

``invoke_method`` 输入示例：

.. code-block:: json

   {
     "packageName": "cn.am7code.tools",
     "processName": "cn.am7code.tools",
     "methodName": "echo",
     "params": {
       "message": "hello"
     },
     "timeoutMs": 5000
   }

返回示例：

.. code-block:: json

   {
     "ok": true,
     "requestId": "req_xxx",
     "result": {
       "ok": true,
       "received": {
         "message": "hello"
       }
     }
   }

内部通信模型
---------------

当前实现使用 **广播发现 + 动态 TCP bridge**：

::

   电脑 MCP Client
       -> XiaoHeiHook MCP HTTP Server
       -> XiaoHeiHook 动态 TCP bridge
       -> 目标 App JsHookRuntime
       -> JS register_method handler

目标 App 第一次调用 ``xhh.rpc.register_method(...)`` 时才会初始化内部连接。如果没有任何远程方法注册，不会广播、不创建 socket、不启动 heartbeat。

发现流程：

1. 目标 App 发送有序广播 ``top.lovepikachu.XiaoHeiHook.MCP_BRIDGE_DISCOVER``。
2. 如果 MCP 没开启或没有响应，注册直接 ignored，不报错、不重试。
3. 如果收到响应，目标 App 获取当前 bridge 的 host、port 和内部 token。
4. 目标 App 连接 ``127.0.0.1:<动态端口>``，发送注册 frame。

这样可以兼容隐藏应用列表场景：目标 App 不需要查询 XiaoHeiHook 包，也不需要绑定 exported service。

生命周期
-----------

注册方法只保存在内存中，不持久化。

目标 App 成功注册后，会和 XiaoHeiHook MCP 进程保持 TCP 长连接。目标 App 关闭、崩溃或进程被杀时，连接断开，XiaoHeiHook 会立即清理该 session 下的所有方法和 pending 调用。

当目标脚本调用 ``unregister_method`` 或 ``unregister_all_methods`` 后，如果没有剩余远程方法，目标 Runtime 会主动关闭 bridge 连接。

重启与调试提示
-----------------

WebIDE 或 App 内触发“同步并重启”时，XiaoHeiHook 会优先使用 root 执行：

::

   am force-stop <packageName>

如果没有 root 或终止失败，会在对应 App 日志中写入提示，要求用户手动终止并重启。App UI 内触发时会额外 Toast 提示“终止程序失败，请手动终止后重启；已尝试打开应用”。即使终止失败，XiaoHeiHook 仍会尝试通过启动入口打开目标 App。

日志排查
-----------

MCP bridge 相关日志统一使用：

::

   XiaoHeiHook-MCP-Bridge

常用命令：

::

   adb logcat | findstr /i "XiaoHeiHook-MCP-Bridge"

常见日志含义：

``MCP bridge discovery broadcast``
    目标 App 正在查询当前 bridge 地址。

``reply MCP bridge discovery``
    XiaoHeiHook MCP 已响应 host/port。

``MCP bridge discovery no response``
    MCP 未运行或未响应，本次注册会 ignored。

``MCP bridge TCP connected``
    目标 App 已连接 bridge。

``register socket method``
    方法已注册到 registry。

``MCP bridge session offline``
    目标 App 断开，方法已自动注销。
