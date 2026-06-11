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

.. seealso::

   Remote Preferences、每应用独立运行索引、hash 配置和目标 App 私有缓存的完整开发说明，见
   :ref:`development-remote-preferences-cache`。

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
   * - ``<packageName>_script_index_json``
     - 当前应用独立的脚本运行索引。目标进程优先读取这个 key，避免同步单个应用时覆盖其他应用的索引。
   * - ``<packageName>_script_sync_manifest_json``
     - 当前应用独立的同步清单。记录远程文件名、原始路径、SHA-256，用于增量同步和清理当前应用的过期 Remote Files。
   * - ``<packageName>_script_hash_config_json``
     - 当前应用独立的 hash 校验配置。目标进程读取 Remote File 或目标私有缓存时用它校验内容。
   * - ``script_index_json`` / ``script_sync_manifest_json`` / ``script_hash_config_json``
     - 旧版全局 key。新版本主要用于兼容、全量同步 union 诊断和迁移 fallback，不再作为目标进程的首选索引。
   * - ``app_enabled_<packageName>``
     - 应用总开关。关闭后该应用不会执行任何脚本。
   * - ``script_enabled_<packageName>_<scriptId>``
     - 某个应用中某个脚本的启用状态。
   * - ``script_settings_<packageName>_<scriptId>``
     - 某个应用中某个脚本的用户设置值。
   * - ``cache_scripts_to_private_dir_<packageName>``
     - 是否允许当前应用读取和写入目标私有脚本缓存。运行时以这个 Remote Preferences key 为准。
   * - ``target_script_cache_dir_<packageName>``
     - 当前应用的目标缓存目录。默认 ``.xhh_scripts``，位于目标 App 的 ``filesDir`` 下。

脚本如何被启用或禁用
--------------------

脚本是否执行由多层条件共同决定：

.. code-block:: text

   app_enabled_<packageName> 必须为 true
      ↓
   <packageName>_script_index_json 中必须存在脚本
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
     - 从已有脚本 metadata 缓存中筛选当前应用已勾选脚本，只同步这些脚本，并写入当前应用自己的 ``<packageName>_script_index_json`` 等运行时 key。
   * - 同步并重启应用
     - 同步后调用应用控制逻辑重启目标 App，适合验证 ``package-loaded`` 或 ``package-ready`` 时机执行的脚本。

同步流程如下：

.. code-block:: text

   用户点击同步当前应用
      ↓
   ScriptRepository 从 metadata 缓存读取脚本列表
      ↓
   只筛选当前应用已勾选且 supportsPackage=true 的脚本
      ↓
   读取这些脚本的入口文件、目录 JS、settings.json 和 assets
      ↓
   为每个文件生成 remoteName 并计算 SHA-256
      ↓
   未变化的 Remote File 跳过写入
      ↓
   变化的 Remote File 覆盖写入
      ↓
   更新 <packageName>_script_index_json
      ↓
   更新 <packageName>_script_sync_manifest_json
      ↓
   更新 <packageName>_script_hash_config_json
      ↓
   如果启用目标私有缓存，执行 Root 同步或等待目标进程自缓存
      ↓
   目标应用重启后读取当前包名自己的运行索引

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

目标进程加载时，``HookEntry`` 会优先读取当前包名对应的 ``<packageName>_script_index_json``。如果这个 key 不存在，才回退读取旧版全局 ``script_index_json``。

匹配成功后，``HookEntry`` 会读取当前应用对应的 ``<packageName>_script_hash_config_json``，先尝试命中目标 App 私有缓存；缓存未命中时，再通过 ``openRemoteFile(script.remoteName)`` 读取入口脚本源码。无论来自缓存还是 Remote File，源码都必须通过 SHA-256 校验，然后才会交给 ``JsHookRuntime`` 执行。

多文件脚本中，入口脚本通过 ``require`` 读取依赖文件时，运行时会根据脚本索引中的 ``files`` 映射找到对应 Remote File 或目标私有缓存文件。

.. important::

   WebIDE 中点击保存只会保存到本地脚本目录。只有执行同步后，目标 App 进程才能读取到新版本脚本。
   每个应用都有自己的运行索引；同步单个应用不会覆盖其他应用的 ``<packageName>_script_index_json``。
