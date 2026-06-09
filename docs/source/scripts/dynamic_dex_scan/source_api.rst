动态 Dex Source API
================================================================================

.. module:: XiaoHeiHook.dex

本页参考 Python 文档的接口写法整理 ``DexApiFacade`` 暴露到 JS 层的所有主要方法。
每个方法独立成条目，包含源码定义、参数、返回值、文件名规则和示例。新脚本应使用
JS 对象和 JS 数组，不再依赖 Java ``List`` / ``Map`` 的 ``get`` 链式访问。

源码文件
--------------------------------------------------------------------------------

.. list-table:: Dex 相关源码
   :header-rows: 1
   :widths: 38 62

   * - 文件
     - 作用
   * - ``script/JsHookRuntime.java``
     - 在 grant 允许时向 Rhino ``scope`` 注入全局 ``dex``。
   * - ``script/JsApiValueNormalizer.java``
     - 将 Java ``Map``、``List``、数组、JSON 转为 JS 对象或数组。
   * - ``dex/DexApiFacade.java``
     - 定义 ``dex.*`` JS facade、Dex view、搜索模型与返回结构。
   * - ``dex/DexCookieDumper.java``
     - 收集 ``DexFile.mCookie`` / ``mInternalCookie`` 并交给 native dump。
   * - ``cpp/xhh_dex_memory_scanner.cpp``
     - cookie dump、内存 dex 扫描和文件写出。
   * - ``dex/DexClassLoadDumper.java``
     - Class.dexCache dump 与 ClassLoader 记忆。
   * - ``dex/DexRuntimeRegistry.java``
     - 动态 ClassLoader 与 dex source 注册表。

Grant 与返回值规则
--------------------------------------------------------------------------------

常用 grant：``dex.read``、``dex.search``、``dex.dump``、``dex.full``、``dex.debug``。
脚本逻辑优先使用 ``paths``、``results``、``methods``、``files``、``dumped``、
``candidates`` 等稳定字段。``sources``、``details``、``diagnostics`` 仅用于日志排障。

``dumpDexCookies`` 文件名规则
--------------------------------------------------------------------------------

``dumpDexCookies`` 的默认输出目录为：

.. code-block:: text

   /data/user/0/<packageName>/code_cache/xhh_dumpdex

默认文件名由 native 层 ``make_cookie_dump_path`` 生成，格式为：

.. code-block:: text

   cookie_<index>_<addressHex>_<fileSize>.dex

例如：

.. code-block:: text

   cookie_000_71d518e000_3777364.dex

字段含义：

* ``cookie_``：cookie dump 文件默认前缀。
* ``index``：三位十进制序号，例如 ``000``、``001``。
* ``addressHex``：ART DexFile 内存基址的十六进制文本。
* ``fileSize``：Dex 文件大小，十进制字节数。

.. note::

   ``ret.paths`` 是权威结果，脚本不要自行拼文件名，也不需要再扫描目录兜底。

已移除的过渡接口
--------------------------------------------------------------------------------

``1.30 (106)`` 后不再作为 JS facade 暴露：``scanDumpDir``、``fromDumpDir``、
``dumpCookieDex``、``dumpFromCookies``、``dumpRawDex``、``traceStrings``、
``inspectDumpedMethod``、``findMethodInCookieDumps``、``dumpTargetDex``、
``dumpDexForMethod``、``dumpUnpackedDexForMethod``。新脚本直接使用稳定返回值，例如
``dumpDexCookies(...).paths``。

打开 Dex Source
--------------------------------------------------------------------------------

.. function:: dex.fromLoader(loader)

   从 ``ClassLoader`` 打开可解析的 Dex source。

   :param loader: Java ``ClassLoader`` 对象；省略时使用默认 loader。
   :return: ``DexFileView``。

.. function:: dex.fromFile(path)

   从 dex/apk/jar/zip 文件打开 ``DexFileView``。

   :param string path: 文件路径。
   :return: ``DexFileView``。

.. function:: dex.fromRuntime()

   打开 ``DexRuntimeRegistry`` 捕获的运行时 source。

   :return: ``DexFileView``。

.. function:: dex.fromMemory()

   打开最近一次内存扫描记录的 source。

   :return: ``DexFileView``。

.. function:: dex.fromClassDumps(options)

   打开 class dexCache dump 目录中的文件。

   :param object options: 可包含 ``dir``、``prefix``、``maxFiles``。
   :return: ``DexFileView``。

.. function:: dex.fromBytes(bytes, name)

   当前版本不支持直接从字节数组打开，调用会抛出 ``UnsupportedOperationException``。

.. function:: dex.fingerprint(query)

   兼容型 view 入口。新脚本进行搜索时优先使用 ``dex.findMethods(query)``。

搜索与检查
--------------------------------------------------------------------------------

.. function:: dex.findMethods(query)

   按类名、方法名、proto、字符串、invoke 或 smali 特征搜索方法。

   :param object query: 查询对象。空条件会被拒绝。
   :return: JS 数组。每项包含 ``className``、``methodName``、``proto``、``descriptor``、
            ``path``、``score``、``reasons``、``strings``、``invokes``、``smaliHead``。

   常用查询字段：

   .. list-table:: query 字段
      :header-rows: 1
      :widths: 28 72

      * - 字段
        - 说明
      * - ``path`` / ``file`` / ``dexPath``
        - 指定单个 dex/apk 文件路径，推荐使用 ``dumpDexCookies`` 返回的 ``paths``。
      * - ``loader``
        - 指定 ClassLoader。
      * - ``className`` / ``class``
        - 限定类名。
      * - ``methodName`` / ``name``
        - 限定方法名。
      * - ``proto`` / ``descriptor``
        - 限定方法原型，例如 ``()Z``。
      * - ``strings``
        - 方法体内必须包含的字符串特征。
      * - ``invokeContains`` / ``invokesContains``
        - invoke 文本必须包含的片段。
      * - ``smaliContains`` / ``smaliKeywords`` / ``smaliKeyword`` / ``smali``
        - smali 文本特征，可只靠 smali 定位方法。
      * - ``limit``
        - 最大返回数量。
      * - ``includeSmali`` / ``smaliChars``
        - 是否返回 smali 片段和最大字符数。

   **示例：找到第一个命中后停止：**

   .. code-block:: javascript

      let found = null;

      xhh.each(paths, function (path, i) {
          const results = dex.findMethods({
              path: path,
              smaliContains: [
                  'const-string "am7_dev_vip_override"',
                  'Ljava/lang/System;->currentTimeMillis()J'
              ],
              limit: 1,
              includeSmali: true
          });

          if (results.length === 0) return true;

          const m = results[0];
          found = {
              dexIndex: i,
              dexPath: path,
              className: m.className,
              methodName: m.methodName,
              proto: m.proto
          };
          return false;
      });

.. function:: dex.findMethod(query)

   返回一个最佳命中。

   :param object query: 同 ``findMethods``。
   :return: 命中对象；未找到返回 ``null``。

.. function:: dex.inspectMethodInFile(options)

   精确检查指定文件中的指定方法，不负责搜索。

   :param string options.path: dex 文件路径，必填。
   :param string options.className: 类名，必填。
   :param string options.methodName: 方法名，必填。
   :param string options.proto: 方法原型，可选。
   :param array options.strings: 必须出现的字符串特征，可选。
   :param array options.invokeContains: 必须出现的 invoke 片段，可选。
   :param array options.smaliContains: 必须出现的 smali 片段，可选。
   :return: JS 对象，包含 ``ok``、``found``、``classFound``、``methodFound``、
            ``featuresOk``、``missingStrings``、``missingInvokeContains``、
            ``missingSmaliContains``。

.. function:: dex.locateMethodInCookieDumps(options)

   在 cookie dump 文件中定位指定方法所在 dex。

   :param string options.dir: dump 目录，默认 ``xhh_dumpdex``。
   :param string options.prefix: 文件名前缀，默认 ``cookie_``。
   :param string options.className: 类名，必填。
   :param string options.methodName: 方法名，必填。
   :param string options.proto: 方法原型，可选。
   :return: JS 对象，常用字段 ``ok``、``found``、``path``、``className``、``methodName``、``proto``。

.. function:: dex.dumpDecryptedDexForMethod(options)

   严格定位并导出目标方法所在 dex。

   :param string options.className: 目标类名，必填。
   :param string options.methodName: 目标方法名，必填。
   :param string options.proto: 方法原型，可选。
   :param string options.outputDir: 输出目录。
   :param string options.exportName: 导出文件名。
   :return: JS 对象。

.. function:: dex.dumpAndInspectMethod(options)

   组合 ``dumpDecryptedDexForMethod`` 与 ``inspectMethodInFile``。仍要求明确目标方法。

.. function:: dex.findSource(methodOrClassOrLoader)

   根据 Java ``Method``、``Class`` 或 ``ClassLoader`` 解析可能的 dex source。

   :return: JS 对象，常用字段 ``paths``、``sources``。

Dump 与内存
--------------------------------------------------------------------------------

.. function:: dex.dumpDexCookies(options)

   基于 ``DexFile.mCookie`` / ``mInternalCookie`` dump 当前加载的 dex。

   :param object options: 配置对象。
   :param ClassLoader options.loader: 应用 ClassLoader；推荐在 ``Application.attach`` 后传入 ``context.getClassLoader()``。
   :param string options.outputDir: 输出目录，默认 ``/data/user/0/<package>/code_cache/xhh_dumpdex``。
   :param string options.cookieDir: ``outputDir`` 的旧别名；新脚本优先写 ``outputDir``。
   :param boolean options.clearOutputDir: dump 前清空输出目录。
   :param boolean options.clearCookieDir: dump 前清空 cookie 目录。
   :param number options.maxDexBytes: 单个 dex 最大字节数，硬上限 ``512 * 1024 * 1024``。
   :param number options.maxDumpCount: 最大 dump 数量，默认 ``128``，硬上限 ``1024``。
   :param boolean options.includeParents: 是否包含父 ClassLoader。
   :param boolean options.includeThreadContext: 是否包含线程上下文 ClassLoader。
   :param boolean options.registerSources: 是否把 dump 结果注册到 runtime source。
   :return: JS 对象，常用字段 ``ok``、``strategy``、``outputDir``、``count``、
            ``paths``、``dumpedPaths``、``sources``、``cookieCount``。

   **文件名：** 默认写出 ``cookie_<index>_<addressHex>_<fileSize>.dex``。

   **示例：**

   .. code-block:: javascript

      const ret = dex.dumpDexCookies({
          loader: loader,
          outputDir: outputDir,
          clearOutputDir: true,
          clearCookieDir: true,
          maxDexBytes: 512 * 1024 * 1024
      });

      const paths = ret.paths || [];
      xposed.i(TAG, 'dump count=' + ret.count);

.. function:: dex.scanMemory(options)

   扫描内存中的 dex 候选。

   :return: JS 对象，常用字段 ``ok``、``count``、``candidates``。

.. function:: dex.dumpMemory(options)

   扫描并 dump 内存 dex。

   :return: JS 对象，常用字段 ``ok``、``count``、``paths``、``dumped``、``candidates``。

   **文件名：** 默认写出 ``memdex_<index>_<addressHex>_<fileSize>.dex``。

.. function:: dex.dumpMemoryRaw(options)

   raw/salvage 内存 dump。适合排障，不保证每个结果都是完整 dex。

.. function:: dex.scanMemoryStrings(options)

   扫描内存字符串。

   :return: ``{ ok, count, hits }``。

.. function:: dex.clearDumpDir(options)

   清理 dump 目录。

   :return: ``{ dir, deleted, failed }``。

.. function:: dex.dumpSources(options)

   枚举 dump 目录中的 dex-like 文件。仅用于展示/排障，脚本控制流优先使用 ``ret.paths``。

.. function:: dex.repairDex(path, options)

   修复单个 dex 文件。

.. function:: dex.repairDumpDir(options)

   批量修复 dump 目录。

.. function:: dex.memorySources()

   返回最近一次内存扫描记录的 source 数组。

Class dump 与运行时来源
--------------------------------------------------------------------------------

.. function:: dex.classLoadDumpStatus()

   返回 class dump 状态对象。

.. function:: dex.classDexDumpStatus()

   ``classLoadDumpStatus`` 的同义状态入口。

.. function:: dex.classDexDumpDir()

   返回 class dex dump 输出目录。

.. function:: dex.classDexDumpSources()

   返回已 dump 的 class source JS 数组。

.. function:: dex.dumpClassDex(classNameOrArray)
.. function:: dex.dumpClassDex(classNameOrArray, loader)
   :no-index:

   dump 指定类名或类名数组对应的 dexCache。

   :return: ``{ ok, count, paths, dumped, failed }``。

.. function:: dex.dumpLoadedClassDex(classObject)

   对已拿到的 Java ``Class`` 对象 dump dexCache。

   :return: ``{ ok, className, path, paths, size }``。

.. function:: dex.rememberAppClassLoader(loader)

   记录脚本发现的应用 ClassLoader。

.. function:: dex.appClassLoaders()

   返回已记忆 ClassLoader JS 数组。

.. function:: dex.classDumpDirStatus(options)

   诊断 class dump 目录与候选来源数量。

.. function:: dex.runtimeSources()

   返回运行时捕获的 source 数组，每项含 ``path``、``type``、``loaderId``、``origin``。

.. function:: dex.runtimeLoaders()

   返回运行时捕获的 loader 数组，每项含 ``id``、``type``、``description``。

.. function:: dex.watchLoaders()

   安装或确认动态 ClassLoader 捕获 hook。

.. function:: dex.registerLoader(loader, path)

   注册 ClassLoader 与 dex 路径。``loader`` 必须是 ClassLoader，``path`` 必须是非空字符串。

   :return: ``{ ok, loaderId, path }``。

限制
--------------------------------------------------------------------------------

.. function:: dex.setLimits(options)

   设置搜索和反汇编限制。

   :param number options.maxDexBytes: 最大 dex 文件大小。
   :param number options.maxClasses: 最大类数量。
   :param number options.maxMethods: 最大方法数量。
   :param number options.maxSmaliChars: 返回 smali 最大字符数。
   :return: ``{ ok, limits }``。

.. function:: dex.limits()

   读取当前限制。

   :return: ``{ ok, limits }``。

DexFileView 方法
--------------------------------------------------------------------------------

.. function:: view.sourceCount()

   返回 source 数量。

.. function:: view.sourceSummary()

   返回 source 摘要字符串。

.. function:: view.skippedSources()
.. function:: view.errors()
.. function:: view.sources()

   返回打开 source 时的跳过项、错误项和 source 列表。集合返回值均为 JS 数组。

.. function:: view.classes()

   返回类列表 JS 数组。

.. function:: view.findClass(descriptor)
.. function:: view.findClassByName(className)

   查找类，返回 ``DexClassView`` 或 ``null``。

.. function:: view.strings()

   返回全局字符串 JS 数组。

.. function:: view.findMethods(query)
.. function:: view.findMethod(query)

   在当前 view 内搜索方法。

DexClassView 方法
--------------------------------------------------------------------------------

.. attribute:: cls.descriptor
.. attribute:: cls.className
.. attribute:: cls.sourcePath

   类描述符、Java 类名和来源路径。

.. function:: cls.methods()
.. function:: cls.fields()
.. function:: cls.strings()

   返回方法、字段、字符串 JS 数组。

.. function:: cls.findMethod(name)
.. function:: cls.findMethod(name, proto)
   :no-index:

   查找类内方法。

DexMethodView 方法
--------------------------------------------------------------------------------

.. attribute:: method.className
.. attribute:: method.name
.. attribute:: method.descriptor
.. attribute:: method.returnType
.. attribute:: method.classDescriptor
.. attribute:: method.parameters

   方法身份字段。

.. function:: method.isStatic()
.. function:: method.isConstructor()

   返回布尔值。

.. function:: method.loader()

   返回默认 ClassLoader。

.. function:: method.strings()
.. function:: method.invokes()
.. function:: method.instructions()

   返回方法体字符串、invoke 引用和指令摘要 JS 数组。

.. function:: method.smali()

   返回 smali 文本。

.. function:: method.toMethod(loader)

   转为 Java ``Executable``。

DexSearchResult / DexMethodHit 方法
--------------------------------------------------------------------------------

.. function:: results.size()

   返回命中数量。

.. function:: results.first()
.. function:: results.best()

   返回第一条命中或 ``null``。

.. function:: results.all()
.. function:: results.hits()
.. function:: results.toList()

   返回所有命中的 JS 数组。

.. function:: hit.toMethod(loader)

   将命中转为 Java ``Executable``。

.. function:: hit.smali()
.. function:: hit.instructions()
.. function:: hit.strings()
.. function:: hit.invokes()
.. function:: hit.toMap()

   返回命中的 smali、指令、字符串、invoke 和稳定对象。

完整示例：DumpDex 后通过 Smali 搜索并停止
--------------------------------------------------------------------------------

.. code-block:: javascript

   const TAG = 'DumpDexSmaliSearch';
   const SMALI_FEATURES = [
       'const-string "am7_dev_vip_override"',
       'Ljava/lang/System;->currentTimeMillis()J'
   ];

   let found = null;

   const dumpRet = dex.dumpDexCookies({
       loader: loader,
       outputDir: outputDir,
       clearOutputDir: true,
       clearCookieDir: true,
       maxDexBytes: 512 * 1024 * 1024
   });

   const paths = dumpRet.paths || [];

   xhh.each(paths, function (path, i) {
       const results = dex.findMethods({
           path: path,
           smaliContains: SMALI_FEATURES,
           limit: 1,
           includeSmali: true
       });

       if (results.length === 0) return true;

       const m = results[0];
       found = {
           dexIndex: i,
           dexPath: path,
           className: m.className,
           methodName: m.methodName,
           proto: m.proto
       };
       return false;
   });

   if (found) {
       xposed.i(TAG, 'found in dex=' + found.dexPath);
       xposed.i(TAG, 'method=' + found.className + '.' + found.methodName + found.proto);
   }