.. _development-remote-preferences-cache:

脚本缓存运行机制
============================================================

本节说明 XiaoHeiHook 如何在模块、管理端和目标进程之间使用 LSPosed Remote Preferences，
以及脚本源码会被缓存到哪些位置。这里描述的是运行时机制，不是用户界面的操作说明。

整体职责划分
------------------------------

XiaoHeiHook 运行时有四类存储位置，每一类负责不同问题：

.. list-table:: 运行时存储位置
   :header-rows: 1
   :widths: 30 70

   * - 位置
     - 作用
   * - 公共脚本目录
     - 用户编辑脚本的源目录，例如 ``Documents/XiaoHeiHook``。管理端和 WebIDE 修改这里的源文件。
   * - LSPosed Remote Files
     - 保存同步后的脚本源码、目录脚本依赖、``settings.json`` 和 ``assets`` 资源。目标进程通过 ``openRemoteFile(remoteName)`` 读取。
   * - LSPosed Remote Preferences
     - 保存应用开关、脚本开关、用户设置、每个应用独立的运行索引、同步清单、hash 校验表和缓存控制开关。
   * - 目标 App 私有缓存目录
     - 可选缓存。保存目标进程可以直接读取的脚本副本，路径位于目标 App 自己的 ``filesDir`` 下。

设计原则是：**Remote Preferences 决定什么可以执行，Remote Files 保存可信源码，目标私有缓存只做加速和离线兜底**。
目标私有缓存不能绕过 Remote Preferences 自己决定脚本是否有效。

Remote Preferences 的 group
--------------------------------------------

管理端和目标进程读取的 Remote Preferences 使用同一个配置组：

.. code-block:: text

   XiaoHeiHookSetting

管理端通过绑定到 LSPosed 的 ``XposedService`` 写入这个 group。目标进程中的 ``HookEntry`` 在模块入口中通过
LSPosed/libxposed 提供的远程偏好接口读取同一个 group。

Remote Preferences 分成三类：

- 用户可见状态：应用总开关、脚本开关、脚本设置值、日志持久化开关、Root 同步开关。
- 运行时索引：每个应用独立的 ``script_index_json``、``script_sync_manifest_json``、``script_hash_config_json``。
- 缓存控制：是否读取目标私有缓存、目标缓存目录、是否需要清理旧缓存。

应用和脚本开关记录
----------------------------------

应用总开关使用包名作为 key 的一部分：

.. code-block:: text

   app_enabled_<packageName>

例如：

.. code-block:: text

   app_enabled_cn.am7code.tools=true

脚本开关也按应用和脚本 ID 组合记录：

.. code-block:: text

   script_enabled_<packageName>_<scriptId>

例如：

.. code-block:: text

   script_enabled_cn.am7code.tools_qidian.toolbox.log=true

这意味着同一个脚本在不同应用中可以有不同启用状态。``HookEntry`` 执行脚本前会同时检查
``app_enabled_<packageName>`` 和 ``script_enabled_<packageName>_<scriptId>``。

脚本用户设置值按同样的应用维度保存：

.. code-block:: text

   script_settings_<packageName>_<scriptId>

运行时注入 ``settings`` 对象时，会先读取脚本索引中的 ``settingsSchema`` 默认值，再用
``script_settings_<packageName>_<scriptId>`` 中保存的用户值覆盖。

每个应用独立的运行时索引
------------------------------------------

当前版本不再使用单个全局 ``script_index_json`` 作为目标进程的主要运行索引。每个应用都有自己的索引 key：

.. code-block:: text

   <packageName>_script_index_json
   <packageName>_script_sync_manifest_json
   <packageName>_script_hash_config_json

例如：

.. code-block:: text

   cn.am7code.tools_script_index_json
   cn.am7code.tools_script_sync_manifest_json
   cn.am7code.tools_script_hash_config_json

这样同步单个应用时，只会覆盖这个应用自己的运行时索引，不会把其他应用的索引冲掉。旧的全局 key 仍保留：

.. code-block:: text

   script_index_json
   script_sync_manifest_json
   script_hash_config_json

这些全局 key 主要用于兼容旧版本、全量同步后的 union 诊断以及迁移兜底。目标进程读取时优先使用
``<packageName>_script_index_json``，如果不存在才回退到旧的 ``script_index_json``。这个回退只是为了旧数据迁移，
不应作为新同步流程的主要路径。

``script_index_json`` 记录了什么
------------------------------------------------

每个应用自己的 ``<packageName>_script_index_json`` 是一个 JSON 数组。数组中的每个对象对应一个已经同步、
并且对当前应用有效的脚本。管理端在 ``ScriptMetadata.toJson(...)`` 中生成这些字段。

常见字段如下：

.. list-table:: script index 字段
   :header-rows: 1
   :widths: 30 70

   * - 字段
     - 说明
   * - ``id``
     - 脚本 ID。来自 metadata 的 ``@id``，没有时使用文件名推导。
   * - ``name`` / ``version`` / ``author`` / ``description``
     - 脚本展示信息。
   * - ``targets``
     - ``@target`` 解析结果。用于判断脚本是否支持当前包名。
   * - ``processes``
     - ``@process`` 解析结果。用于判断脚本是否支持当前进程。
   * - ``runAt``
     - ``@run-at`` 解析结果，例如 ``package-loaded`` 或 ``package-ready``。
   * - ``grants``
     - ``@grant`` 解析结果。
   * - ``remoteName``
     - 入口脚本在 Remote Files 中的远程文件名。
   * - ``path`` / ``scriptPath``
     - 脚本源文件相对公共脚本目录的路径。
   * - ``kind``
     - ``file`` 或 ``directory``。目录脚本通常以 ``index.js`` 为入口。
   * - ``entryPath``
     - 实际入口文件路径。
   * - ``rootPath``
     - 目录脚本的根目录。
   * - ``hasSettings`` / ``settingsPath``
     - 是否存在 ``settings.json`` 以及它的路径。
   * - ``settingsSchema``
     - ``settings.json`` 解析后的 schema。空值、``null`` 或无效 JSON 会被忽略。
   * - ``settingsDefaults``
     - 从 ``settingsSchema`` 计算得到的默认值对象。
   * - ``cacheScriptsToPrivateDir``
     - 生成索引时的提示字段，表示是否有匹配应用启用了目标私有缓存。运行时是否读取缓存仍以 Remote Preferences 开关为准。
   * - ``cacheScriptsToPrivateDirPackages``
     - 开启目标私有缓存的包名列表。用于诊断和兼容，不作为运行时最终准则。
   * - ``targetScriptCacheDirByPackage``
     - 每个包名对应的目标缓存目录。
   * - ``files``
     - JS 入口和依赖文件列表。每项包含 ``path``、``remoteName``、``sha256``。
   * - ``assets``
     - 资源文件列表。每项包含 ``path``、``remoteName``、``sha256``。

目标进程不会因为脚本出现在索引里就直接执行它。索引只是候选列表。最终还要检查应用开关、脚本开关、
``@target``、``@process`` 和 ``@run-at``。

同步清单记录了什么
----------------------------------

每个应用自己的同步清单保存在：

.. code-block:: text

   <packageName>_script_sync_manifest_json

它由 ``buildSyncManifestJson(...)`` 生成，结构大致包含：

.. code-block:: json

   {
     "version": 1,
     "packageName": "cn.am7code.tools",
     "generatedAt": 1781111010999,
     "fileCount": 2,
     "scripts": [],
     "files": []
   }

其中：

- ``version`` 是清单版本。
- ``packageName`` 是本次清单所属的应用包名。全局 union 清单中可能为 ``null``。
- ``generatedAt`` 是生成时间戳。
- ``fileCount`` 是参与本次同步的 Remote File 数量。
- ``scripts`` 记录每个脚本的入口路径、根目录、远程入口名、JS 文件和 asset 文件。
- ``files`` 是扁平文件列表，用于增量同步和清理过期 Remote Files。

同步时，``syncScriptUnitToRemote(...)`` 会把每个文件的 ``remoteName`` 与 SHA-256 放入当前清单。
如果旧清单中同名文件的 SHA-256 没变，并且 Remote Files 中仍存在该文件，就跳过写入。否则重新写入。

单应用同步时只使用当前应用自己的清单判断 stale 文件，并且会排除其他应用清单仍引用的 Remote File。
这可以避免同步 A 应用时误删 B 应用仍在使用的远程脚本文件。

Hash 配置记录了什么
--------------------------------

每个应用自己的 hash 配置保存在：

.. code-block:: text

   <packageName>_script_hash_config_json

它由 ``buildScriptHashConfigJson(...)`` 生成，核心字段包括：

.. list-table:: hash config 字段
   :header-rows: 1
   :widths: 30 70

   * - 字段
     - 说明
   * - ``version``
     - 配置版本。
   * - ``packageName``
     - 当前配置所属应用。全局 union 配置中可能为 ``null``。
   * - ``generatedAt``
     - 生成时间戳。
   * - ``fileCount``
     - 参与记录的文件数量。
   * - ``byRemoteName``
     - ``remoteName -> sha256`` 映射。
   * - ``byPath``
     - ``path -> sha256`` 映射。
   * - ``byScriptId``
     - 按脚本 ID 分组的 hash 信息，包含入口、JS 文件、asset 和 remoteName 映射。
   * - ``byPackage``
     - 按包名分组的脚本、remoteName hash 和 path hash。
   * - ``files``
     - 扁平文件列表。每项记录 ``scriptId``、``path``、``remoteName``、``sha256`` 和 ``type``。

``HookEntry`` 读取脚本或依赖文件时，会用 hash 配置校验 Remote File 内容。校验失败会抛出错误并跳过对应脚本，
避免目标进程执行损坏或不一致的源码。

目标私有缓存的开关和目录
--------------------------------------------

目标私有缓存由 Remote Preferences 控制。关键 key 如下：

.. list-table:: 目标缓存相关 key
   :header-rows: 1
   :widths: 38 62

   * - key
     - 说明
   * - ``cache_scripts_to_private_dir_<packageName>``
     - 是否允许目标进程读取和写入目标 App 私有缓存。默认值为 ``true``。
   * - ``target_script_cache_dir_<packageName>``
     - 缓存目录名，位于目标 App 的 ``filesDir`` 下。默认 ``.xhh_scripts``。
   * - ``target_script_cache_cleanup_requested_<packageName>``
     - 请求清理旧目标缓存。关闭缓存开关时会写入，目标进程下次进入时尝试清理。
   * - ``useRootScriptCacheSync``
     - 是否允许管理端在 Root 可用时直接同步到目标 App 私有目录。默认在 Root 可用时开启。

``HookEntry.isTargetCacheEnabled(...)`` 的判断只相信 Remote Preferences：

.. code-block:: text

   prefs != null && prefs.getBoolean("cache_scripts_to_private_dir_<packageName>", true)

如果 Remote Preferences 不可用，目标进程不会读取本地缓存。即使 ``.xhh_scripts`` 目录里有旧文件，也不会被当成有效脚本来源。
这是重启后排错时最重要的一条规则：**缓存是否有效由 LSPosed Remote Preferences 决定，不由缓存目录自己决定**。

目标缓存目录如何规范化
----------------------------------

默认缓存目录是：

.. code-block:: text

   .xhh_scripts

假设目标包名是 ``com.example``，默认路径为：

.. code-block:: text

   /data/user/0/com.example/files/.xhh_scripts/

其中脚本副本保存到：

.. code-block:: text

   /data/user/0/com.example/files/.xhh_scripts/scripts/

缓存索引保存到：

.. code-block:: text

   /data/user/0/com.example/files/.xhh_scripts/index.json

如果用户自定义目录为：

.. code-block:: text

   cache/xhh_scripts

则目标路径是：

.. code-block:: text

   /data/user/0/com.example/files/cache/xhh_scripts/

目录规范化规则来自 ``ScriptPrefs.normalizeTargetScriptCacheDir(...)`` 和 ``TargetScriptCache.normalizeRootDir(...)``：

- 空字符串回退到 ``.xhh_scripts``。
- 绝对路径会去掉开头的 ``/``，不会允许写出 ``filesDir``。
- 包含 ``.``、``..`` 的路径回退到默认目录。
- 自定义目录的每一段只允许 ``A-Z``、``a-z``、``0-9``、``.``、``_``、``-``，长度不超过 80。
- 只有内置默认目录 ``.xhh_scripts`` 保持隐藏目录。
- 自定义目录如果以 ``.`` 开头，会去掉前导点，避免把用户自定义目录强行变成隐藏目录。

``TargetScriptCache.root(...)`` 还会通过 canonical path 校验最终目录必须位于目标 App 的 ``filesDir`` 内。
如果校验失败，会回退到默认目录。

目标私有缓存文件如何命名
----------------------------------------

目标私有缓存不是按原始文件名直接保存，而是按缓存 ID 和 SHA-256 保存。文件名规则类似：

.. code-block:: text

   <safeCacheId>_<sha256>.js

缓存 ID 由脚本 ID、文件路径和 remoteName 组合而来。路径中的非法字符会被替换为下划线。
这让同一个脚本的入口、依赖文件和资源文件可以分别缓存，也让不同版本的内容通过 SHA-256 区分。

``index.json`` 由 ``TargetScriptCache.updateIndex(...)`` 维护，结构类似：

.. code-block:: json

   {
     "version": 1,
     "packageName": "com.example",
     "rootDir": ".xhh_scripts",
     "scripts": [
       {
         "scriptId": "demo.script_index.js.remoteName",
         "sha256": "...",
         "path": "scripts/demo_....js",
         "updatedAt": 1781111010999
       }
     ]
   }

读取缓存时，``TargetScriptCache.readIfHashMatch(...)`` 会重新计算文件 SHA-256。
如果实际 hash 与索引或 hash 配置中的期望值不同，会删除该文件并返回 ``null``。

Root 同步和目标进程自缓存
------------------------------------------

目标私有缓存有两条写入路径。

第一条是 Root 同步路径：

.. code-block:: text

   管理端同步脚本
      ↓
   判断 useRootScriptCacheSync=true 且 Root 可用
      ↓
   在管理端 cache/xhh_target_cache_sync 下生成临时文件和 install.sh
      ↓
   su -c sh install.sh
      ↓
   写入目标 App dataDir/files/<cacheDir>/scripts/
      ↓
   写入 index.json
      ↓
   chown 到目标 App uid:gid
      ↓
   目录 chmod 700，文件 chmod 600

Root 脚本不会只猜 ``/data/user/0``。它会通过 PackageManager 的 ``ApplicationInfo.dataDir`` 作为 hint，
并扫描常见多用户路径，例如：

.. code-block:: text

   /data/user/*/<packageName>
   /data/data/<packageName>
   /data_mirror/data_ce/null/*/<packageName>
   /data_mirror/data_de/null/*/<packageName>
   /data_mirror/data_ce/*/*/<packageName>
   /data_mirror/data_de/*/*/<packageName>

同时还会尝试从 ``cmd package dump``、``dumpsys package`` 或 ``pm dump`` 中解析 ``dataDir=...``。
如果找到多个匹配数据目录，会对每个目录执行同步，适配多用户、工作空间或分身环境。

第二条是目标进程自缓存路径：

.. code-block:: text

   目标 App 被 Hook
      ↓
   HookEntry 读取每应用 script_index_json
      ↓
   需要读取脚本源码
      ↓
   如果缓存开关开启，先尝试 readIfHashMatch(filesDir, cacheDir, cacheId, sha256)
      ↓
   缓存命中：直接使用目标私有缓存
      ↓
   缓存未命中：openRemoteFile(remoteName)
      ↓
   校验 Remote File SHA-256
      ↓
   写入目标 App 自己的 filesDir/<cacheDir>/scripts/

当 Root 不可用、Root 同步失败或设置页关闭 Root 同步时，会走这条路径。写入动作发生在目标 App 进程中，
因此不会碰到管理端没有目标私有目录权限的问题。

关闭缓存时如何清理
--------------------------------

当用户关闭某个应用的“脚本缓存到私有目录”后，管理端会把：

.. code-block:: text

   cache_scripts_to_private_dir_<packageName>=false

写入 Remote Preferences，并设置或保留清理请求：

.. code-block:: text

   target_script_cache_cleanup_requested_<packageName>=true

清理有两条路径：

- 如果 Root 可用，管理端会直接删除目标 App 的 ``filesDir/<cacheDir>`` 和默认 ``.xhh_scripts`` 旧目录。
- 如果 Root 不可用，目标进程下次被 Hook 时会看到缓存开关关闭，然后通过自己的 ``filesDir`` 删除缓存目录。

``HookEntry`` 清理时仍会检查目标目录必须位于目标 App 的 ``filesDir`` 内，避免误删其他路径。

管理端同步时的记录流程
------------------------------------------

管理端同步当前应用时，核心流程可以按代码理解为：

.. code-block:: text

   syncEnabledScriptsForPackageToRemote(packageName)
      ↓
   applyScriptRootFromPrefs(prefs)
      ↓
   packageManifest(prefs, packageName)
      ↓
   listRemoteFileNames(service)
      ↓
   selectedScriptSourcesForPackageFromCache(prefs, packageName)
      ↓
   readPublicScriptSourceFromMetadata(metadata)
      ↓
   syncScriptUnitToRemote(service, source, previousManifest, remoteFiles, currentFiles)
      ↓
   scripts.toJson(prefs)
      ↓
   buildSyncManifestJson(packageName, scripts, files)
      ↓
   buildScriptHashConfigJson(packageName, prefs, scripts, files)
      ↓
   cleanupStaleRemoteFiles(...)
      ↓
   prefs.edit().putString(<packageName>_script_index_json, ...)
      ↓
   prefs.edit().putString(<packageName>_script_sync_manifest_json, ...)
      ↓
   prefs.edit().putString(<packageName>_script_hash_config_json, ...)
      ↓
   finishTargetPrivateCacheSync(...)

这里有两个容易误解的点：

- 同步当前应用不会重新扫描全部脚本目录。它优先使用 ``SCRIPT_METADATA_CACHE_JSON``、旧运行索引和各应用运行索引合并出来的 metadata。
- 真正扫描公共脚本目录通常发生在用户手动刷新脚本列表、首次没有 metadata 缓存，或显式全量同步时。

``selectedScriptSourcesForPackageFromCache(...)`` 会从缓存 metadata 中筛选：

.. code-block:: text

   script.supportsPackage(packageName)
   && script_enabled_<packageName>_<scriptId> == true

筛选后只读取这些脚本的入口文件，不会对未选中脚本做完整解析。

目标进程执行时的读取流程
------------------------------------------

目标进程中的 ``HookEntry`` 按当前包名读取运行索引：

.. code-block:: text

   <packageName>_script_index_json
      ↓ 如果不存在
   script_index_json 旧全局索引 fallback

然后对每个脚本执行过滤：

.. code-block:: text

   app_enabled_<packageName> == true
      ↓
   script_enabled_<packageName>_<scriptId> == true
      ↓
   @target 支持当前 packageName
      ↓
   @process 支持当前 processName
      ↓
   @run-at 匹配当前生命周期
      ↓
   读取脚本源码

读取脚本源码时，会按以下顺序：

.. code-block:: text

   读取 <packageName>_script_hash_config_json
      ↓
   如果 cache_scripts_to_private_dir_<packageName> 为 true
      ↓
   尝试读取目标私有缓存并校验 SHA-256
      ↓ 缓存命中
   使用目标私有缓存源码
      ↓ 缓存未命中
   openRemoteFile(remoteName)
      ↓
   校验 Remote File SHA-256
      ↓
   如果缓存开关仍然开启，写入目标私有缓存
      ↓
   交给 JsHookRuntime 执行

注意：脚本必须先出现在当前应用自己的 ``<packageName>_script_index_json`` 中。
即使目标私有缓存目录里存在旧文件，如果索引里没有这个脚本，``HookEntry`` 也不会凭空加载它。

诊断日志记录了什么
--------------------------------

打开 XiaoHeiHook 后，诊断日志使用 tag：

.. code-block:: text

   XiaoHeiHook-PrefsDiag

它会在两个时机打印：

- ``MainActivity.onCreate``，如果 LSPosed 服务还没有绑定，会提示等待服务绑定。
- ``xposed-service-bound``，服务绑定完成后读取 Remote Preferences 并打印完整状态。

总览日志会记录：

- Remote Preferences 总 key 数。
- 已启用应用数量。
- 旧全局 ``script_index_json`` 的长度、hash 和脚本数。
- 每应用 ``script_index_json``、``script_hash_config_json``、``script_sync_manifest_json`` key 数。
- 全局和每应用 hash/manifest 文件数量。
- metadata 缓存数量、更新时间、缓存文件数。
- ``scriptRoot``、``disableFileLogging``、``useRootScriptCacheSync``。

每个已启用应用会记录：

- ``appEnabled`` 与 ``containsAppKey``。
- ``cacheToPrivateDir`` 与 ``containsCacheKey``。
- ``targetCacheDirRaw``、``targetCacheDirNormalized`` 与 ``containsDirKey``。
- ``cleanupRequested`` 与 ``containsCleanupKey``。
- ``packageIndexKey``、``containsPackageIndex``、``packageIndexLength``、``packageIndexCount``。
- ``selectedScripts``：Remote Preferences 中勾选的脚本数量。
- ``selectedInPackageIndex``：勾选且已进入当前应用索引的脚本数量。
- ``supportedPackageIndex``：当前应用索引中支持该包名的脚本数量。
- ``hashEntriesForPackage``：当前应用 hash 配置中记录的文件数量。
- 目标应用 ``uid`` 和 ``dataDir``。

如果某个脚本开关为 true，但没有进入当前应用自己的 ``script_index_json``，诊断会打印：

.. code-block:: text

   enabled=true but missing from package script_index_json

这通常说明用户勾选状态还在，但没有对当前应用执行过有效同步，或者同步时没有从 metadata 缓存读到该脚本。
重启后脚本不生效时，这条日志非常关键。

和重启行为的关系
--------------------------------

重启手机后，目标 App 进程会重新进入 ``HookEntry``，所有运行时状态都会从 Remote Preferences 和 Remote Files 冷启动读取。
因此必须满足：

- 当前应用自己的 ``<packageName>_script_index_json`` 存在并包含脚本。
- 当前应用自己的 ``<packageName>_script_hash_config_json`` 存在并包含对应 hash。
- ``app_enabled_<packageName>`` 为 true。
- ``script_enabled_<packageName>_<scriptId>`` 为 true。
- 如果需要目标私有缓存，``cache_scripts_to_private_dir_<packageName>`` 为 true。

重启前“看起来正常”并不代表 Remote Preferences 里保存了完整运行索引。目标进程可能已经加载过脚本，或者目标缓存已经在旧进程中命中过。
重启会清空这些运行时侥幸状态，所以不完整的索引会立刻暴露。
