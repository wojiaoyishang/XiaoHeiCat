JS API 参考（1.30 / 106）
================================================================================

本页按 Python 文档的写法组织 XiaoHeiHook 注入到 JavaScript 运行时的全局接口：
每个对象先说明源码定义，再把每个函数单独列为 ``.. function::`` 条目，便于查阅。
``1.30 (106)`` 是一次 JS API 返回值稳定化和语法边界明确化版本，新脚本应优先使用
JS 对象、JS 数组和对象字面量参数。

.. module:: XiaoHeiHook.js

源码位置
--------------------------------------------------------------------------------

.. list-table:: 全局对象与源码定义
   :header-rows: 1
   :widths: 24 38 38

   * - 全局对象
     - 源码定义
     - 说明
   * - ``settings``
     - ``JsHookRuntime.SettingsFacade``
     - 当前脚本设置的只读视图。
   * - ``env``
     - ``JsHookRuntime.Env``
     - 当前脚本、目标包、目标进程、ClassLoader 等环境信息。
   * - ``console``
     - ``JsHookRuntime.Console``
     - 控制台日志输出。
   * - ``Java``
     - ``JsHookRuntime.JavaBridge``
     - Java 类型加载、反射方法、字段、数组创建。
   * - ``xposed``
     - ``JsHookRuntime.XposedFacade``
     - LSPosed 生命周期、Hook、Invoker、日志、堆栈、Remote File。
   * - ``xhh``
     - ``JsHookRuntime.XhhFacade`` / ``RpcFacade``
     - XiaoHeiHook 版本、grant、JS 引擎能力、对象类型、稳定遍历和 RPC。
   * - ``debuggerx``
     - ``JsHookRuntime.DebuggerFacade``
     - WebIDE 软断点、行断点和调试日志。
   * - ``dex``
     - ``DexApiFacade``
     - Dex dump、搜索、运行时来源和内存扫描。需要 Dex grant 才注入。
   * - ``require``
     - ``JsHookRuntime.CommonJsRequire``
     - CommonJS 风格相对路径模块加载。

返回值稳定化规则
--------------------------------------------------------------------------------

``1.30 (106)`` 后，面向脚本逻辑的复杂返回值会通过
``JsApiValueNormalizer.toJs(value)`` 转换。脚本应使用 ``obj.field``、``arr[i]``、
``arr.length``，不要再写 ``map.get('x')`` 或 ``list.get(i)``。

.. list-table:: Java 到 JS 转换规则
   :header-rows: 1
   :widths: 32 68

   * - Java 侧值
     - JS 侧结果
   * - ``Map`` / ``JSONObject``
     - JS 普通对象，例如 ``ret.ok``、``ret.paths``。
   * - ``List`` / ``Iterable`` / Java 数组 / ``JSONArray``
     - JS 数组，例如 ``arr.length``、``arr[i]``。
   * - ``Throwable``
     - 调试对象 ``{ type, message, text }``；日志接口仍能识别真正的 Java ``Throwable``。
   * - ``File``
     - 文件绝对路径字符串。
   * - ``Class`` / ``ClassLoader`` / ``Method`` / ``Constructor`` / ``Field``
     - 保留为 Java bridge 对象，供 Hook 或反射继续使用。
   * - 字符串、数字、布尔值、``null``
     - 原样返回。

JS 脚本语法边界说明
--------------------------------------------------------------------------------

.. warning::

   当前 Rhino 可以解析 ``const``，但在部分 Android / LSPosed 环境下不能可靠实现
   ``for`` 循环体内 ``const`` 的每轮重新绑定。典型现象是：
   ``for (...) { const path = paths[i]; }`` 第二轮仍读到第一轮的 ``path``。

推荐通用语法：

* 全局常量、固定配置可以继续使用 ``const TAG = 'Demo'``。
* 循环内会随下标变化的临时变量推荐使用 ``let``。
* 遍历 ``paths``、``results``、``methods`` 等 JS 数组时，推荐使用 ``xhh.each(array, callback)``。
* 需要确认当前引擎能力时使用 ``xhh.jsEngine()``。
* 需要确认一个值是 JS 对象还是 Java bridge 对象时使用 ``xhh.objectKind(value)``。

**推荐写法：**

.. code-block:: javascript

   const paths = ret.paths || [];

   xhh.each(paths, function (path, i) {
       xposed.i(TAG, 'dex[' + i + ']=' + path);
       return true;
   });

**不推荐写法：**

.. code-block:: javascript

   for (let i = 0; i < paths.length; i++) {
       const path = paths[i];   // Rhino 上可能固定为第一轮值
       xposed.i(TAG, path);
   }

``settings`` 设置接口
--------------------------------------------------------------------------------

源码定义：``JsHookRuntime.SettingsFacade``。``settings`` 是脚本配置的只读视图。

.. function:: settings.get(key)

   读取指定设置项。

   :param string key: 设置项名称。
   :return: 返回 JS 稳定值；不存在时返回 ``null``。

.. function:: settings.get(key, defaultValue)

   读取指定设置项，不存在时返回默认值。

   :param string key: 设置项名称。
   :param defaultValue: 默认值；会按稳定化规则返回。
   :return: JS 稳定值。

   **示例：**

   .. code-block:: javascript

      const enabled = settings.get('enabled', true);
      if (!enabled) return;

.. function:: settings.has(key)

   判断设置项是否存在。

   :param string key: 设置项名称。
   :return: ``boolean``。

.. function:: settings.all()

   返回所有设置的副本。

   :return: JS 普通对象。

.. attribute:: settings.raw

   合并后的原始 JSON 字符串。

``env`` 环境对象
--------------------------------------------------------------------------------

源码定义：``JsHookRuntime.Env``。``env`` 是只读快照，不是函数集合。

.. attribute:: env.packageName

   当前目标包名。

.. attribute:: env.processName

   当前目标进程名。

.. attribute:: env.scriptName

   当前脚本名。

.. attribute:: env.scriptId

   当前脚本 ID。

.. attribute:: env.modulePackageName

   XiaoHeiHook 模块包名。

.. attribute:: env.classLoader

   当前默认 ``ClassLoader`` Java 对象。

.. attribute:: env.defaultClassLoader

   当前生命周期参数提供的默认 ``ClassLoader``。

``console`` 日志接口
--------------------------------------------------------------------------------

源码定义：``JsHookRuntime.Console``。所有参数会被转成字符串后写入日志。

.. function:: console.log(...messages)

   输出普通日志。

.. function:: console.info(...messages)

   输出 info 日志。

.. function:: console.warn(...messages)

   输出 warn 日志。

.. function:: console.error(...messages)

   输出 error 日志。

``Java`` 反射接口
--------------------------------------------------------------------------------

源码定义：``JsHookRuntime.JavaBridge``。

.. function:: Java.type(className)

   加载 Java 类。

   :param string className: Java 类名，例如 ``android.app.Application``。
   :return: Java ``Class`` 对象。

.. function:: Java.use(className)

   ``Java.type`` 的别名。

.. function:: Java.classForName(className)

   使用默认 ClassLoader 加载类。

.. function:: Java.classForName(className, initialize, loader)

   使用指定初始化参数和 ClassLoader 加载类。

.. function:: Java.method(clazzOrName, methodName)

   获取无参方法。

.. function:: Java.method(clazzOrName, methodName, parameterTypes)

   获取指定参数列表的方法。

   :param clazzOrName: Java ``Class`` 对象或类名字符串。
   :param string methodName: 方法名。
   :param parameterTypes: JS 数组或 Java Class 数组。
   :return: Java ``Method`` 对象。

.. function:: Java.constructor(clazzOrName)

   获取无参构造器。

.. function:: Java.constructor(clazzOrName, parameterTypes)

   获取指定参数构造器。

.. function:: Java.field(clazzOrName, fieldName)

   获取字段对象。

.. function:: Java.newArray(componentType, length)

   创建 Java 数组。

``xposed`` 生命周期接口
--------------------------------------------------------------------------------

源码定义：``JsHookRuntime.XposedFacade``。

.. function:: xposed.onPackageLoaded(callback)

   注册包加载回调。

   :param callback: ``function (param) { ... }``。
   :return: ``void``。

   ``param`` 常用方法包括 ``getPackageName()``、``getProcessName()``、
   ``getDefaultClassLoader()``、``getClassLoader()``、``isFirstPackage()``。

.. function:: xposed.onPackageReady(callback)

   注册包 ready 回调。

.. function:: xposed.onModuleLoaded(callback)

   注册模块加载回调。

.. function:: xposed.onSystemServerStarting(callback)

   注册 system server 启动回调。

``xposed`` Hook 接口
--------------------------------------------------------------------------------

.. function:: xposed.hook(executable)

   创建 Hook builder。

   :param executable: Java ``Method`` 或 ``Constructor``。
   :return: ``JsHookBuilder``。

   **示例：**

   .. code-block:: javascript

      xposed.hook(method)
          .setPriority(xposed.PRIORITY_DEFAULT)
          .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
          .intercept(function (chain) {
              const args = chain.getArgsMutable();
              return chain.proceed(args);
          });

.. function:: xposed.hookClassInitializer(clazz)

   Hook 指定类的静态初始化器。

.. function:: xposed.deoptimize(executable)

   请求框架对目标方法去优化。

.. function:: xposed.getInvoker(methodOrConstructor)

   获取低层 invoker，用于原始调用、special 调用或构造实例。

.. attribute:: xposed.PRIORITY_HIGHEST
.. attribute:: xposed.PRIORITY_HIGH
.. attribute:: xposed.PRIORITY_DEFAULT
.. attribute:: xposed.PRIORITY_LOW
.. attribute:: xposed.PRIORITY_LOWEST

   Hook 优先级常量。

.. attribute:: xposed.ExceptionMode.PROTECTIVE
.. attribute:: xposed.ExceptionMode.PROPAGATE

   Hook 异常处理模式。

``JsHookBuilder`` 方法
--------------------------------------------------------------------------------

.. function:: builder.setPriority(priority)

   设置 Hook 优先级并返回 builder。

.. function:: builder.setExceptionMode(mode)

   设置异常处理模式并返回 builder。

.. function:: builder.intercept(callback)

   安装 Hook。

   :param callback: ``function (chain) { ... }``。
   :return: ``JsHookHandle``。

``JsChain`` 方法
--------------------------------------------------------------------------------

.. function:: chain.getExecutable()

   返回当前 Hook 的 ``Method`` 或 ``Constructor``。

.. function:: chain.getThisObject()

   返回当前 ``this`` 对象；静态方法为 ``null``。

.. function:: chain.getArgs()

   返回当前参数的 JS 稳定数组副本。

.. function:: chain.getArg(index)

   返回指定参数。

.. function:: chain.getArgsMutable()

   返回可修改的 Java ``Object[]`` 副本，适合传给 ``chain.proceed(args)``。

.. function:: chain.proceed()

   执行原始方法。

.. function:: chain.proceed(args)

   使用新的参数数组执行原始方法。

.. function:: chain.proceedWith(thisObject)

   使用指定 ``this`` 执行原始方法。

.. function:: chain.proceedWith(thisObject, args)

   使用指定 ``this`` 和参数执行原始方法。

``JsHookHandle`` 方法
--------------------------------------------------------------------------------

.. function:: handle.getExecutable()

   返回 Hook 的目标 ``Executable``。

.. function:: handle.unhook()

   卸载 Hook。

``JsInvoker`` 方法
--------------------------------------------------------------------------------

.. function:: invoker.setType(type)

   设置调用类型。

.. function:: invoker.invoke(thisObject)
.. function:: invoker.invoke(thisObject, args)

   调用方法。

.. function:: invoker.invokeSpecial(thisObject)
.. function:: invoker.invokeSpecial(thisObject, args)

   special 调用方法。

.. function:: invoker.newInstance()
.. function:: invoker.newInstance(args)

   调用构造器创建实例。

.. function:: invoker.newInstanceSpecial(subClass)
.. function:: invoker.newInstanceSpecial(subClass, args)

   special 构造。

``xposed`` 日志接口
--------------------------------------------------------------------------------

.. function:: xposed.log(priority, tag, msg)

   按 Android 日志优先级输出。

.. function:: xposed.log(priority, tag, msg, throwable)

   输出日志并附带异常；非 Java ``Throwable`` 会安全转成字符串。

.. function:: xposed.v(tag, msg)
.. function:: xposed.d(tag, msg)
.. function:: xposed.i(tag, msg)
.. function:: xposed.w(tag, msg)
.. function:: xposed.e(tag, msg)
.. function:: xposed.e(tag, msg, any)
.. function:: xposed.wtf(tag, msg)

   各日志级别快捷方法。``xposed.e(tag, msg, any)`` 的第三参可以是 Java
   ``Throwable``、JS Error、普通对象、字符串或 ``null``。

``xposed`` 堆栈接口
--------------------------------------------------------------------------------

.. function:: xposed.getJavaStackTraceString()

   返回完整 Java 堆栈字符串。

.. function:: xposed.getAppStackTraceString()

   返回过滤后的应用堆栈字符串。

.. function:: xposed.getJavaStackTrace()

   返回 JS 数组，每项包含 ``className``、``methodName``、``fileName``、``lineNumber`` 等。

.. function:: xposed.getAppStackTrace()

   返回过滤后的 JS 堆栈数组。

.. function:: xposed.stackTrace()
.. function:: xposed.stackTrace(options)

   返回 JS 堆栈数组。``options`` 支持 ``appOnly``、``maxDepth``、``skipNative``。

.. function:: xposed.printJavaStackTrace(tag, title)
.. function:: xposed.printAppStackTrace(tag, title)
.. function:: xposed.printJavaStack(tag, title)
.. function:: xposed.printAppStack(tag, title)

   直接打印堆栈到日志。

``xposed`` 框架与 Remote File 接口
--------------------------------------------------------------------------------

.. function:: xposed.getApiVersion()
.. function:: xposed.getFrameworkName()
.. function:: xposed.getFrameworkVersion()
.. function:: xposed.getFrameworkVersionCode()

   读取底层框架信息。

.. function:: xposed.getFrameworkProperties()

   返回框架属性；若底层返回 Map/List，会稳定化为 JS 对象/数组。

.. function:: xposed.getModuleApplicationInfo()

   返回模块应用信息；能稳定化的字段会转换成 JS 对象。

.. function:: xposed.getRemotePreferences(group)

   返回 LSPosed Remote Preferences Java 对象。

.. function:: xposed.listRemoteFiles()

   返回 Remote File 文件名 JS 字符串数组。

.. function:: xposed.openRemoteFile(name)

   打开 Remote File，返回 ``ParcelFileDescriptor``。

.. function:: xposed.readRemoteText(name)

   读取 Remote File 文本。

``xposed.raw`` 低层接口
--------------------------------------------------------------------------------

源码定义：``JsHookRuntime.RawFacade``。raw 接口主要用于诊断，不推荐作为业务脚本主路径。

.. function:: xposed.raw.enabled()

   返回 raw 能力是否可用。

.. function:: xposed.raw.getInterface()

   返回底层接口对象。

.. function:: xposed.raw.unwrap(value)

   拆开 Rhino 包装对象。

.. function:: xposed.raw.typeOf(value)

   返回 Java 类名或 ``null``。

.. function:: xposed.raw.call(target, methodName)
.. function:: xposed.raw.call(target, methodName, args)

   反射调用方法；返回 Map/List 时会稳定化。

.. function:: xposed.raw.field(targetOrClass, fieldName)
.. function:: xposed.raw.method(clazzOrName, methodName)
.. function:: xposed.raw.method(clazzOrName, methodName, parameterTypes)
.. function:: xposed.raw.constructor(clazzOrName)
.. function:: xposed.raw.constructor(clazzOrName, parameterTypes)

   raw 反射辅助。

``xhh`` 基础接口
--------------------------------------------------------------------------------

源码定义：``JsHookRuntime.XhhFacade``。

.. function:: xhh.info()

   返回 XiaoHeiHook 版本和当前脚本环境信息。

   :return: JS 对象，常用字段包括 ``versionName``、``versionCode``、``versionLabel``、
            ``packageName``、``processName``、``scriptId``、``scriptName``。

.. function:: xhh.hasGrant(grant)

   判断当前脚本是否拥有 grant。

.. function:: xhh.jsEngine()

   返回当前 JS 引擎能力信息。

   :return: JS 对象，包含 ``engine``、``implementation``、
            ``constLoopLexicalBinding``、``recommendedLoopBinding``、``stableIterationApi``。

.. function:: xhh.each(items, callback)

   稳定遍历 JS 数组、Java 数组、Iterable 或单个值。

   :param items: 要遍历的集合。
   :param callback: ``function (item, index)``。返回 ``false`` 时停止遍历。
   :return: 遍历统计对象。

   **示例：**

   .. code-block:: javascript

      xhh.each(paths, function (path, i) {
          xposed.i(TAG, 'path[' + i + ']=' + path);
          return true;
      });

.. function:: xhh.objectKind(value)

   判断值是 JS 对象、Java 对象、基础类型、``null`` 还是 ``undefined``。

   :return: JS 对象，字段包括 ``kind``、``isJsObject``、``isJavaObject``、
            ``isPrimitive``、``isNull``、``isUndefined``、``rawClass``、``javaClass``、``text``。

.. function:: xhh.isJsObject(value)

   判断值是否为普通 JS 对象或 JS 数组。

.. function:: xhh.isJavaObject(value)

   判断值是否为 Java bridge 对象。

``xhh.rpc`` RPC 接口
--------------------------------------------------------------------------------

源码定义：``JsHookRuntime.RpcFacade``。用于 MCP/外部客户端调用脚本方法。

.. function:: xhh.rpc.register_method(name, handler)

   注册 RPC 方法。

.. function:: xhh.rpc.register_method(name, options, handler)

   注册 RPC 方法并指定说明、schema 等元信息。

   :return: ``{ ok, methodName, registered }``。

.. function:: xhh.rpc.unregister_method(name)

   注销指定 RPC 方法。

.. function:: xhh.rpc.unregister_all_methods()

   注销当前脚本注册的全部 RPC 方法。

``RpcCallContext`` 方法
--------------------------------------------------------------------------------

.. function:: ctx.isCancelled()

   判断 RPC 调用是否已取消。

.. function:: ctx.throwIfCancelled()

   已取消时抛出异常。

.. function:: ctx.log(message)

   向调用端写入日志。

``debuggerx`` 调试接口
--------------------------------------------------------------------------------

.. function:: debuggerx.isEnabled()

   判断 WebIDE 调试是否启用。

.. function:: debuggerx.pause(name)

   触发软断点。

.. function:: debuggerx.breakpoint(name)
.. function:: debuggerx.breakpoint(name, locals)

   触发命名断点。

.. function:: debuggerx.hasLineBreakpoints()

   判断是否存在行断点。

.. function:: debuggerx.log(name, value)

   输出调试变量。

``require`` 模块加载接口
--------------------------------------------------------------------------------

.. function:: require(path)

   加载相对路径 CommonJS 模块。

   :param string path: 例如 ``./logger``。
   :return: 模块 ``exports`` 对象。

``dex`` 接口索引
--------------------------------------------------------------------------------

``dex`` 的完整方法说明见 :doc:`dynamic_dex_scan/source_api`。这里仅列出常用入口：

* ``dex.dumpDexCookies(options)``：cookie 脱壳，返回 ``paths``。
* ``dex.findMethods(query)``：按字符串、invoke、proto 或 smali 特征搜索方法。
* ``dex.findMethod(query)``：返回一个最佳命中或 ``null``。
* ``dex.inspectMethodInFile(options)``：精确检查指定方法。
* ``dex.dumpClassDex(classNameOrArray[, loader])``：class dexCache dump。
* ``dex.scanMemory(options)`` / ``dex.dumpMemory(options)``：内存扫描与 dump。
* ``dex.runtimeSources()`` / ``dex.runtimeLoaders()``：运行时 source 与 loader。
