脚本同步说明
====================

脚本同步是 XiaoHeiHook 的核心流程之一。它的作用是把手机管理端脚本目录中的脚本文件、脚本索引、开关状态和设置项信息，写入 LSPosed/libxposed 能在目标进程中读取的位置。

什么是脚本同步
--------------------

用户在管理端或 WebIDE 中编辑的脚本，默认保存在公共脚本目录：

.. code-block:: text

   Documents/XiaoHeiHook/

这里的文件属于管理端可见的脚本源文件。目标 App 进程不能直接读取管理端的普通文件路径，所以需要把脚本同步到 LSPosed 提供的 Remote Files / Remote Preferences 中。

同步后的数据流如下：

.. code-block:: text

   Documents/XiaoHeiHook 本地脚本
      ↓ ScriptRepository 扫描与解析
   LSPosed Remote Files 保存脚本源码
   LSPosed Remote Preferences 保存脚本索引与开关
      ↓ HookEntry 在目标进程读取
   JsHookRuntime 执行 JS 脚本

为什么需要脚本同步
--------------------

Android 应用之间存在沙盒隔离，目标 App 进程无法直接访问 XiaoHeiHook 管理端的数据目录。即使脚本放在公共目录，也不能保证目标进程在所有 Android 版本和权限状态下稳定读取。

同步机制解决以下问题：

- 让目标进程通过 ``openRemoteFile`` 稳定读取脚本源码。
- 让目标进程通过 ``getRemotePreferences`` 读取脚本索引和开关状态。
- 让脚本源文件、依赖文件、hash 校验信息保持一致。
- 让 WebIDE 或手机端修改脚本后，可以显式应用到目标进程。

同步之后脚本叫什么
--------------------

同步到 Remote Files 后，脚本不再直接使用原始文件名，而是使用安全的远程文件名。

入口脚本的远程文件名规则为：

.. code-block:: text

   script_<cleanScriptId>_<sha1(scriptId).take(8)>

例如脚本 ID 为：

.. code-block:: text

   demo.okhttp.logger

同步后的入口 Remote File 名称可能类似：

.. code-block:: text

   script_demo_okhttp_logger_a1b2c3d4

多文件脚本中的依赖文件使用另一套命名规则：

.. code-block:: text

   script_asset_<cleanScriptId>_<cleanPath>_<sha1(scriptId:path).take(10)>

例如：

.. code-block:: text

   okhttp_logger/logger.js

可能被同步为：

.. code-block:: text

   script_asset_demo_okhttp_logger_okhttp_logger_logger.js_9f8e7d6c5b

.. note::

   WebIDE 和手机端仍然展示原始路径，例如 ``okhttp_logger/index.js``。远程文件名只用于 LSPosed Remote Files 内部读取。

同步后保存到哪里
--------------------

同步后的内容分为两类保存。

脚本源码保存到 **Remote Files**：

.. code-block:: text

   XposedService.openRemoteFile(remoteName)

脚本索引、同步清单、应用开关、脚本开关、设置项值保存到 **Remote Preferences**，默认 group 为：

.. code-block:: text

   XiaoHeiHookSetting

其中常用 key 如下：

.. list-table:: Remote Preferences key
   :header-rows: 1
   :widths: 36 64

   * - key
     - 说明
   * - ``script_index_json``
     - 当前同步后的脚本索引。目标进程通过它知道有哪些脚本、脚本 ID、远程文件名、目标包名、运行时机等信息。
   * - ``script_sync_manifest_json``
     - 同步清单。记录远程文件名、原始路径、SHA-256，用于增量同步和清理过期 Remote Files。
   * - ``app_enabled_<packageName>``
     - 应用总开关。关闭后该应用不会执行任何脚本。
   * - ``script_enabled_<packageName>_<scriptId>``
     - 某个应用中某个脚本的启用状态。
   * - ``script_settings_<packageName>_<scriptId>``
     - 某个应用中某个脚本的用户设置值。

脚本如何被启用或禁用
--------------------

脚本是否执行由多层条件共同决定：

.. code-block:: text

   app_enabled_<packageName> 必须为 true
      ↓
   script_index_json 中必须存在脚本
      ↓
   脚本 @target 必须匹配当前 packageName
      ↓
   脚本 @process 必须匹配当前 processName
      ↓
   脚本 @run-at 必须匹配当前生命周期
      ↓
   script_enabled_<packageName>_<scriptId> 必须为 true
      ↓
   HookEntry 读取 Remote File 并执行脚本

因此，常见的“不生效”问题需要依次检查：

- 应用总开关是否打开。
- 当前脚本开关是否打开。
- LSPosed 作用域是否包含目标应用。
- ``@target`` 是否写对包名。
- ``@process`` 是否匹配当前进程。
- ``@run-at`` 是否与脚本代码期望一致。
- 修改脚本后是否已经同步。

如何进行同步
--------------------

手机端可以在应用详情页执行同步；WebIDE 可以在 Hook 设置面板中执行同步。

常见操作有三种：

.. list-table:: 同步入口
   :header-rows: 1
   :widths: 28 72

   * - 操作
     - 说明
   * - 重新扫描
     - 只重新读取 ``Documents/XiaoHeiHook`` 中的脚本，不一定写入 Remote Files。适合检查脚本列表是否识别正确。
   * - 同步当前应用
     - 扫描脚本并写入 Remote Files / Remote Preferences。目标进程下次加载时使用新脚本。
   * - 同步并重启应用
     - 同步后调用应用控制逻辑重启目标 App，适合验证 ``package-loaded`` 或 ``package-ready`` 时机执行的脚本。

同步流程如下：

.. code-block:: text

   用户点击同步
      ↓
   ScriptRepository 扫描公共脚本目录
      ↓
   解析 metadata、settings.json、多文件依赖
      ↓
   为每个脚本生成 remoteName
      ↓
   计算每个文件 SHA-256
      ↓
   未变化的 Remote File 跳过写入
      ↓
   变化的 Remote File 覆盖写入
      ↓
   更新 script_index_json
      ↓
   更新 script_sync_manifest_json
      ↓
   目标应用重启后读取新脚本

同步 settings.json 与用户设置
------------------------------------

``settings.json`` 是脚本目录中的 schema 文件。它会随多文件脚本一起进入同步流程，并影响脚本索引中的设置项信息。

用户实际填写的设置值不会写回 ``settings.json``，而是按应用维度保存：

.. code-block:: text

   script_settings_<packageName>_<scriptId>

运行时读取配置时会先取 ``settings.json`` 的默认值，再用用户保存的 values 覆盖：

.. code-block:: text

   settings.json default
      ↓
   用户 values 覆盖
      ↓
   settings.get / settings.all 读取最终结果

这样同一个脚本在不同应用中可以有不同配置。

同步后的执行过程
--------------------

目标进程加载时，``HookEntry`` 会读取 ``script_index_json``，然后对每个脚本做匹配。匹配成功后，通过 ``openRemoteFile(script.remoteName)`` 读取入口脚本源码，并交给 ``JsHookRuntime`` 执行。

多文件脚本中，入口脚本通过 ``require`` 读取依赖文件时，运行时会根据脚本索引中的 ``files`` 映射找到对应 Remote File。

.. important::

   WebIDE 中点击保存只会保存到本地脚本目录。只有执行同步后，目标 App 进程才能读取到新版本脚本。
