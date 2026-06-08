API 定义
===============

版本与 grant
----------------------------------------------------------

动态 Dex / DumpDex 能力在 ``1.20 (102)`` 中继续保留，并新增内部调试日志开关。

常用 grant：

.. list-table:: Dex 相关 grant
   :header-rows: 1
   :widths: 30 70

   * - grant
     - 说明
   * - ``dex.dump``
     - 允许 dump dex、cookie dump 和 DumpDex 相关能力。
   * - ``dex.read``
     - 允许只读 Dex 数据源。
   * - ``dex.search``
     - 允许搜索方法、字符串、调用特征等。
   * - ``dex.full``
     - 允许完整 Dex 能力。
   * - ``dex.debug``
     - 开启 Dex / DumpDex 内部调试日志。只影响日志，不授予 dump 权限。

.. note::
   没有 ``dex.dump``、``dex.read``、``dex.search`` 或 ``dex.full`` 时，不应依赖全局 ``dex`` 对象。

源码位置：

.. list-table:: Dex / DumpDex 源码位置
   :header-rows: 1
   :widths: 42 58

   * - 文件
     - 作用
   * - ``app/src/main/java/top/lovepikachu/XiaoHeiHook/script/JsHookRuntime.java``
     - 根据 grant 注入 ``dex`` 对象，并控制 ``dex.debug`` 日志开关。
   * - ``app/src/main/java/top/lovepikachu/XiaoHeiHook/dex/*``
     - Dex 数据源、方法搜索、dump、内存扫描等实现。
   * - ``app/src/main/java/top/lovepikachu/XiaoHeiHook/XhhConstants.java``
     - 定义 ``DEX_API_VERSION``。

.. tip::
   当前推荐 dump 输出目录为 ``/data/user/0/<目标包>/code_cache/xhh_dumpdex/``。旧文档中出现的 ``xhh_class_dex_cookie`` 不再作为默认输出目录。

DumpDex 主流程 API
------------------

``dex.dumpDecryptedDexForMethod(options)``
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

单一路径脱壳接口。不会走 memory scan、raw salvage 或广泛 fallback。

流程：

#. 使用传入的真实 ``loader`` 加载目标类。
#. 从该 ClassLoader 的 ``DexFile cookie`` dump dex。
#. 在新 dump 的 cookie dex 中定位目标方法。
#. 将目标方法所在 dex 复制到稳定输出路径。

常用参数：

.. code-block:: javascript

   dex.dumpDecryptedDexForMethod({
     loader: appClassLoader,
     className: 'pc.a',
     methodName: 'd',
     proto: '()Z',
     exportName: 'pc_a_d_unpacked.dex',
     clearCookieDir: true,
     includeParents: false,
     includeThreadContext: false,
     maxDumpCount: 64,
     maxFiles: 128,
     maxDexBytes: 512 * 1024 * 1024,
     verbose: true
   })

返回字段：

* ``found``：是否成功定位并导出。
* ``exportedPath``：稳定导出的脱壳 dex 路径。
* ``sourcePath``：命中的原始 cookie dex 路径。
* ``dumpCount`` / ``cookieValueCount`` / ``dexFileObjectCount``。
* ``locate``：定位目标方法的详细结果。

兼容别名：

* ``dex.dumpUnpackedDexForMethod(options)``
* ``dex.dumpTargetDex(options)``
* ``dex.dumpDexForMethod(options)``

``dex.dumpAndInspectMethod(options)``
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

推荐的一步式接口。先调用 ``dumpDecryptedDexForMethod``，再对导出的 dex 调用 ``inspectMethodInFile``。

.. code-block:: javascript

   var result = dex.dumpAndInspectMethod({
     loader: appClassLoader,
     className: 'pc.a',
     methodName: 'd',
     proto: '()Z',
     strings: ['am7_dev_vip_override', 'getString(...)', 'vip', 'nonvip'],
     invokeContains: ['->getString(']
   })

返回字段：

* ``dump``：dump 结果。
* ``inspect``：方法体检查结果。
* ``dumpFound``：是否导出成功。
* ``featuresOk``：方法体是否命中所有特征。

``dex.inspectMethodInFile(options)``
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

只检查一个已存在 dex，不重新 dump。

.. code-block:: javascript

   dex.inspectMethodInFile({
     path: '/data/user/0/cn.am7code.tools/code_cache/xhh_dumpdex/pc_a_d_unpacked.dex',
     className: 'pc.a',
     methodName: 'd',
     proto: '()Z',
     strings: ['am7_dev_vip_override', 'getString(...)', 'vip', 'nonvip'],
     invokeContains: ['->getString('],
     smaliChars: 6000
   })

返回字段包括：

* ``found`` / ``classFound`` / ``methodFound``。
* ``descriptor`` / ``returnType`` / ``parameters`` / ``static``。
* ``strings`` / ``invokes``。
* ``missingStrings`` / ``missingInvokeContains``。
* ``featuresOk``。
* ``smaliHead``。

兼容别名：

* ``dex.inspectDumpedMethod(options)``

``dex.locateMethodInCookieDumps(options)``
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

只在已有 cookie dump 目录中定位目标方法，不重新 dump。

.. code-block:: javascript

   dex.locateMethodInCookieDumps({
     dir: '/data/user/0/cn.am7code.tools/code_cache/xhh_dumpdex',
     prefix: 'cookie_',
     className: 'pc.a',
     methodName: 'd',
     proto: '()Z'
   })

兼容别名：

* ``dex.findMethodInCookieDumps(options)``

旧接口状态
----------

以下接口仍保留，但不再推荐作为加固壳首选路径：

* ``dex.fromFile(path)``
* ``dex.findMethods(query)``
* ``dex.dumpDexCookies(options)``
* ``dex.scanDumpDir(options)``
* ``dex.fromClassDumps(options)``
* ``dex.scanMemory(options)`` / ``dex.fromMemory()``

示例只使用稳定 cookie dump + 精确 inspect 路线。
