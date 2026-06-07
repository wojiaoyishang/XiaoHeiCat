开发与排错
==================

常见问题
-----------------

脚本没有出现在列表中
~~~~~~~~~~~~~~~~~~~~~~

请检查：

- 脚本是否放在 ``Documents/XiaoHeiHook``。
- 单文件脚本是否以 ``.js`` 结尾。
- 多文件脚本目录下是否有 ``index.js``。
- 是否授予管理所有文件权限。

脚本没有运行
~~~~~~~~~~~~~~~~~~~~~~

请检查：

- LSPosed 中是否启用 XiaoHeiHook 模块。
- 目标应用是否在模块作用域内。
- XiaoHeiHook 应用详情页中应用开关是否打开。
- 对应脚本开关是否打开。
- 脚本 ``@target`` 和 ``@process`` 是否匹配。
- 是否同步脚本并重启目标应用。

WebIDE 后台无法访问
~~~~~~~~~~~~~~~~~~~~~~

部分系统会限制后台服务。建议：

- 在 XiaoHeiHook 设置中请求忽略电池优化。
- 保持 WebIDE 前台服务通知存在。
- 使用 ``adb forward`` 访问 ``127.0.0.1``。

Remote Preferences 未连接
~~~~~~~~~~~~~~~~~~~~~~~~~~

如果提示 Remote Preferences 未连接，通常表示 LSPosed 服务尚未连接或模块未正常启用。请确认：

- LSPosed 中已启用模块。
- 管理端能连接 LSPosed 服务。
- 目标应用重新启动后再测试。

调试建议
-----------------

- 先用 ``console.log`` 和 ``xposed.i`` 输出关键路径。
- Hook 前打印目标 ClassLoader 与类名。
- 使用 ``xposed.printAppStackTrace`` 查看调用来源。
- 使用 ``debuggerx.breakpoint`` 在关键位置暂停观察变量。
- 对多进程应用，先确认 ``env.processName`` 是否符合预期。
