日志相关说明
====================

XiaoHeiHook 的日志系统用于把目标 App 进程中的脚本输出回传到管理端。开发脚本、排查 Hook 是否命中、分析参数与异常时，都应该优先查看日志。

日志来源
--------------------

脚本可以通过 ``console`` 或 ``xposed`` 输出日志：

.. code-block:: javascript

   console.log("hello")
   console.warn("warning")
   console.error("error")

   xposed.d("XHH", "debug message")
   xposed.i("XHH", "info message")
   xposed.w("XHH", "warn message")
   xposed.e("XHH", "error message")

``console`` 更接近 WebIDE 终端习惯，``xposed`` 日志更接近 Android 日志级别。两者都会被 ``JsHookRuntime`` 包装成日志事件。

日志回传流程
--------------------

日志产生于目标 App 进程，但展示在 XiaoHeiHook 管理端或 WebIDE 中。中间需要跨进程回传：

.. code-block:: text

   目标 App 进程中的 JS 脚本
      ↓ console / xposed.log
   JsHookRuntime 生成日志事件
      ↓ 显式广播
   LogReceiver 接收日志
      ↓
   AppLogRepository 写入日志文件
      ↓
   手机日志页 / WebIDE 终端读取展示

日志通常按 ``packageName`` 保存，因此不同应用的日志互不混合。

手机端查看日志
--------------------

手机端可以在应用详情页进入日志页面。日志页面负责读取当前应用对应的日志文件，并提供：

- 不自动换行，长日志行可以横向滚动。
- 根据日志级别染色，例如 error、warn、info、debug。
- 支持缩放字号。
- 支持清空日志。
- 支持通过系统选择器使用其他文本编辑器打开日志文件。

当日志文件不存在时，打开外部编辑器前会先创建空日志文件，避免系统选择器没有可打开的 URI。

WebIDE 远程日志
--------------------

WebIDE 通过 HTTP API 获取日志：

.. code-block:: text

   GET /api/logs?packageName=<packageName>
   GET /api/logs/stream?packageName=<packageName>

其中 ``/api/logs`` 用于一次性读取当前日志内容，``/api/logs/stream`` 用于实时流式输出。

前端终端通常使用 SSE 连接日志流：

.. code-block:: javascript

   const source = new EventSource('/api/logs/stream?packageName=' + packageName)
   source.onmessage = function (event) {
       appendLog(event.data)
   }

如果 WebIDE 终端没有实时更新，可以检查：

- 是否选择了正确应用。
- 浏览器 SSE 连接是否断开。
- 目标 App 是否真的执行了脚本。
- 手机端日志页是否能看到同一条日志。

日志文件与外部打开
--------------------

日志由 ``AppLogRepository`` 写入 XiaoHeiHook 管理端可访问的位置。手机端通过 ``FileProvider`` 将日志文件临时授权给外部文本编辑器。

这种方式避免直接暴露私有路径，也符合 Android 新版本对文件 URI 的限制。

日志排错建议
--------------------

- 完全没有日志时，先确认应用开关、脚本开关和 LSPosed 作用域。
- 只有管理端日志、没有脚本日志时，优先检查脚本是否实际执行。
- 日志太长时，应减少高频 Hook 中的输出，避免影响目标 App 性能。
- 输出对象时尽量转换成短字符串，避免广播数据过大。
