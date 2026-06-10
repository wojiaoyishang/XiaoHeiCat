方法定位与检查 API
================================================================================

本章只说明“定位目标 dex / 定位目标方法 / 检查方法体特征”相关接口，采用与
“脚本 API 接口”一致的 ``.. function::`` 风格。完整脚本示例不再放在
``dynamic_dex_scan`` 章节下；脚本应按实际目标自行组合这些接口。

推荐流程
--------------------------------------------------------------------------------

.. list-table:: 定位流程
   :header-rows: 1
   :widths: 24 76

   * - 阶段
     - 推荐接口
   * - 先 dump 已加载 dex
     - 使用 ``dex.dumpDexCookies(options)``，并读取返回值中的 ``paths``。
   * - 不知道类名和方法名
     - 使用 ``dex.scanSmali(options)`` 或 ``dex.forEachMethod(options, callback)`` 按特征搜索。
   * - 已知道类名和方法名
     - 使用 ``dex.locateMethodInCookieDumps(options)`` 精确定位所在 dex。
   * - 已知道 dex 路径和方法
     - 使用 ``dex.inspectMethodInFile(options)`` 检查 strings / invokes / smali 特征。
   * - 需要运行时 Hook
     - 使用 ``loader.loadClass(...)`` 和 ``getDeclaredMethod(...)`` 获取 Java ``Method``。

Dump 接口
--------------------------------------------------------------------------------

.. function:: dex.dumpDexCookies(options)

   基于目标进程当前已加载的 ``DexFile`` cookie dump dex 文件。该接口通常应在
   ``Application.attach`` 之后调用，并传入真实 App ``ClassLoader``。

   :param object options: dump 配置对象。
   :param ClassLoader options.loader: 目标 App 的 ``ClassLoader``。
   :param string options.outputDir: 输出目录；不传时使用默认 ``xhh_dumpdex`` 目录。
   :param boolean options.clearOutputDir: dump 前是否清空输出目录。
   :param number options.maxDexBytes: 单个 dex 最大字节数。
   :param number options.maxDumpCount: 最大 dump 数量。
   :param boolean options.includeParents: 是否包含父 ``ClassLoader``。
   :param boolean options.includeThreadContext: 是否包含线程上下文 ``ClassLoader``。
   :param boolean options.registerSources: 是否将 dump 结果注册到运行时 source 表。
   :return: JS 对象。常用字段包括 ``ok``、``outputDir``、``count``、``paths``、
            ``dumpedPaths``、``sources``、``cookieCount``。

   ``paths`` 是后续搜索接口的推荐输入。返回值已经是 JS 友好对象，脚本可以直接使用
   ``ret.paths.length``、``ret.paths[i]`` 和 ``ret.paths.join(',')``。

未知目标方法搜索接口
--------------------------------------------------------------------------------

.. function:: dex.scanSmali(options)

   在 Java 层按 smali 文本关键词搜索方法。适合“不知道类名和方法名，只知道字符串、
   invoke、算法名、接口路径、VIP 判断特征”等场景。该接口只把命中的方法返回到 JS，
   不会把全部方法体都搬到 JS 层。

   :param object options: 搜索配置对象。
   :param string options.path: 单个 dex/apk 文件路径。
   :param array options.paths: 多个 dex/apk 文件路径，通常来自 ``dumpDexCookies(...).paths``。
   :param ClassLoader options.loader: 也可以直接从 ``ClassLoader`` 解析 source。
   :param array options.keywords: smali 文本关键词。
   :param array options.smaliContains: ``keywords`` 的同义字段。
   :param string options.mode: ``"any"`` 或 ``"all"``。默认 ``"any"``。
   :param string options.classPrefix: 可选类名前缀过滤。
   :param array options.excludeClassPrefixes: 可选排除类名前缀。
   :param boolean options.includeSmali: 是否返回完整或截断后的 smali 文本。
   :param number options.maxSmaliChars: 返回 smali 的最大字符数。
   :param number options.limit: 最大命中数量。
   :param boolean options.verbose: 是否输出扫描进度。
   :return: JS 数组。每项常用字段包括 ``className``、``methodName``、``proto``、
            ``descriptor``、``path``、``score``、``matchedKeywords``、``smaliHead``、``smali``。

   当只是固定关键词搜索时，优先使用本接口；当需要复杂评分、正则或多字段组合判断时，
   使用 ``dex.forEachMethod``。

.. function:: dex.forEachMethod(options, callback)

   在 Java 层遍历方法，并把符合基础过滤条件的方法逐个传给 JS 回调。适合“不知道类名、
   不知道方法名，需要脚本自己用字符串方法判断 smali / strings / invokes”的场景。

   .. tip::

      完整的 ``Smali echo`` 脚本示例见 GitHub 仓库中的 `examples/qidian_dex_search.js <https://github.com/wojiaoyishang/XiaoHeiCat/blob/master/examples/qidian_dex_search.js>`_。

   :param object options: 遍历配置对象。
   :param string options.path: 单个 dex/apk 文件路径。
   :param array options.paths: 多个 dex/apk 文件路径。
   :param ClassLoader options.loader: 直接从 ``ClassLoader`` 解析 source。
   :param string options.classPrefix: 可选类名前缀过滤。
   :param array options.excludeClassPrefixes: 可选排除类名前缀。
   :param boolean options.includeSmali: 是否向回调对象附带 smali 文本。
   :param boolean options.includeStrings: 是否附带方法字符串常量。
   :param boolean options.includeInvokes: 是否附带 invoke 摘要。
   :param number options.maxSmaliChars: smali 最大字符数。
   :param number options.limit: 最多收集多少个 ``callback`` 返回 ``true`` 的结果。
   :param boolean options.stopOnCallbackError: 回调异常时是否停止遍历。
   :param function callback: 回调函数，参数是方法信息对象。
   :return: JS 对象。常用字段包括 ``ok``、``visitedClasses``、``visitedMethods``、
            ``results``、``count``、``callbackErrors``、``errorCount``、``stopped``。

   回调返回值规则：

   .. list-table:: callback 返回值
      :header-rows: 1
      :widths: 28 72

      * - 返回值
        - 含义
      * - ``true``
        - 收集当前方法到 ``ret.results``，继续遍历。
      * - ``"stop"`` / ``"break"``
        - 停止遍历。
      * - ``false`` / ``null`` / ``undefined``
        - 不收集当前方法，继续遍历。

   回调方法对象常用字段包括 ``className``、``methodName``、``proto``、``descriptor``、
   ``path``、``sourceEntry``、``static``、``strings``、``invokes``、``smali`` 和 ``smaliHead``。

已知目标方法定位接口
--------------------------------------------------------------------------------

.. function:: dex.locateMethodInCookieDumps(options)

   在 cookie dump 目录中定位指定方法所在 dex。该接口适合已经知道类名、方法名，
   只需要确认方法属于哪个 dump dex 的场景。

   :param object options: 定位配置对象。
   :param string options.dir: dump 目录。
   :param string options.prefix: dump 文件名前缀，默认 ``cookie_``。
   :param string options.className: 目标 Java 类名，例如 ``"pc.a"``。
   :param string options.methodName: 目标方法名。
   :param string options.proto: 目标方法原型，例如 ``"()Z"``。
   :param number options.maxDexBytes: 最大读取字节数。
   :return: JS 对象。常用字段包括 ``ok``、``found``、``path``、``className``、
            ``methodName``、``proto``。

.. function:: dex.inspectMethodInFile(options)

   精确检查指定 dex 文件中的指定方法，并验证 strings / invoke / smali 特征。该接口不负责搜索，
   调用前应已经知道 ``path``、``className`` 和 ``methodName``。

   :param object options: 检查配置对象。
   :param string options.path: dex 文件路径。
   :param string options.className: 目标 Java 类名。
   :param string options.methodName: 目标方法名。
   :param string options.proto: 方法原型。
   :param array options.strings: 必须出现的字符串特征。
   :param array options.invokeContains: 必须出现的 invoke 片段。
   :param array options.smaliContains: 必须出现的 smali 片段。
   :param number options.maxDexBytes: 最大读取字节数。
   :return: JS 对象。常用字段包括 ``ok``、``found``、``classFound``、``methodFound``、
            ``featuresOk``、``strings``、``invokes``、``missingStrings``、
            ``missingInvokeContains``、``missingSmaliContains``、``smaliHead``。

运行时 Method 获取接口
--------------------------------------------------------------------------------

.. function:: JavaClassWrapper.getDeclaredMethod(name, ...parameterTypes)

   通过 Java 反射获取运行时 ``java.lang.reflect.Method``。在 ``1.32 (109)`` 中，
   ``parameterTypes`` 可以直接写字符串签名，也可以传 ``loader.loadClass(...)`` 得到的 Java ``Class``。

   :param string name: 方法名。
   :param parameterTypes: 参数类型列表，支持字符串类名、基础类型名或 Java ``Class`` 对象。
   :return: Java ``Method`` wrapper，可继续 ``setAccessible(true)`` 并传给 ``xposed.hook``。

   该接口用于把 dex 搜索结果转成真实运行时方法。例如搜索结果给出 ``className``、``methodName``、
   ``proto`` 后，脚本仍需要通过目标 App ``ClassLoader`` 加载类，再按参数类型获取 ``Method``。

选择建议
--------------------------------------------------------------------------------

.. list-table:: 接口选择
   :header-rows: 1
   :widths: 30 70

   * - 情况
     - 建议接口
   * - 只知道关键词或 smali 特征
     - ``dex.scanSmali``。
   * - 不知道目标方法，需要自定义评分或复杂判断
     - ``dex.forEachMethod``。
   * - 已知道类名、方法名和 proto
     - ``dex.locateMethodInCookieDumps`` + ``dex.inspectMethodInFile``。
   * - 已经拿到 dump dex 路径
     - ``dex.scanSmali({ path: ... })`` 或 ``dex.forEachMethod({ path: ... })``。
   * - 已经拿到运行时类
     - ``JavaClassWrapper.getDeclaredMethod``。
