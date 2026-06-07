断点与调试说明
====================

XiaoHeiHook 的断点调试用于在目标 App 运行脚本时，把关键位置、变量快照和调用栈发送回管理端或 WebIDE，帮助开发者分析 Hook 是否命中以及参数是否正确。

脚本级断点
--------------------

脚本可以通过 ``debuggerx`` 主动触发断点：

.. code-block:: javascript

   debuggerx.breakpoint("before-login", {
       user: username,
       url: requestUrl
   })

这不是浏览器 DevTools 的原生断点，而是 XiaoHeiHook 定义的脚本级调试事件。它更适合用在：

- Hook 回调入口。
- 网络请求构造前后。
- 加密、签名、序列化等关键流程。
- 需要查看参数快照的位置。

断点事件流程
--------------------

断点事件的大致流程如下：

.. code-block:: text

   JS 脚本调用 debuggerx.breakpoint
      ↓
   JsHookRuntime / JsDebugTrace 创建调试事件
      ↓
   DebugProtocol 描述事件内容
      ↓
   DebugEventReceiver 接收目标进程事件
      ↓
   DebugEventRepository 缓存事件
      ↓
   手机端 / WebIDE 展示断点、变量和调用栈

断点事件通常包含：

- 断点名称。
- 当前应用包名。
- 当前进程名。
- 当前脚本路径。
- 局部变量快照。
- 可选调用栈。
- 触发时间。

开放目标 App 调试
--------------------

调试目标 App 本身时，脚本运行在目标 App 进程内，而不是运行在 XiaoHeiHook 管理端进程内。因此调试逻辑必须避免长时间阻塞目标 App。

推荐流程：

.. code-block:: text

   开启 WebIDE
      ↓
   打开终端或断点面板
      ↓
   同步脚本
      ↓
   重启目标 App
      ↓
   在目标 App 中手动操作触发业务流程
      ↓
   查看日志、断点事件和变量快照

注意事项：

- 不要在高频方法中大量触发断点。
- 不要在 UI 主线程暂停太久，否则可能导致目标 App 卡顿或 ANR。
- 变量快照不要过大，避免广播或日志传输压力过高。
- 网络、加密、序列化等位置更适合使用断点。

与未来行级调试的关系
--------------------

当前断点更偏向脚本主动打点。后续如果接入 Rhino 行级调试，可以在现有 ``DebugProtocol`` 基础上扩展：

- Monaco 行号断点。
- 文件名 + 行号断点表。
- Step Over / Step Into / Step Out。
- 表达式求值。
- 变量树查看。

这样 WebIDE 可以逐步从“脚本终端 + 主动断点”升级为更接近传统 IDE 的调试体验。
