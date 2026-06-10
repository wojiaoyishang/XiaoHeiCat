JS API 参考
================================================================================

本页按 Python 文档的写法组织 XiaoHeiHook 注入到 JavaScript 运行时的全局接口：
每个对象先说明源码定义，再把每个函数单独列为 ``.. function::`` 条目，便于查阅。
``1.30 (106)`` 是一次 JS API 返回值稳定化和语法边界明确化版本，新脚本应优先使用
JS 对象、JS 数组和对象字面量参数。

``1.30 (107)`` 新增 ``xhh.fs`` 文件与资源桥接接口，用于脚本文件读写、当前脚本
``assets/`` 读取，以及资源复制到目标 App 私有目录。

``1.31 (108)`` 修复 JS Runtime 作用域复用问题：Hook 回调、Java SAM proxy 回调与
MCP/RPC 回调会回到同一个脚本全局作用域执行；同时新增 ``xhh.global``，用于在同一
目标进程内跨脚本、跨回调保存运行期状态。

``1.32 (109)`` 新增 ``Java.to(...)`` 显式类型构造，并强化 Java Bridge 包装方法：
``getDeclaredMethod``、``getMethod``、``getDeclaredConstructor``、``getConstructor``
的签名参数支持类名字符串和基础类型名称；已经是 Java 对象或 Java wrapper 的值传回
Java 时只解包，不会再被当作 JS 原生值二次自动转换。

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
     - ``JavaBridge`` / ``JavaClassWrapper`` / ``JavaObjectWrapper``
     - Java 类型加载、wrapper 调用、反射方法、字段、数组创建。
   * - ``xposed``
     - ``JsHookRuntime.XposedFacade``
     - LSPosed 生命周期、Hook、Invoker、日志、堆栈、Remote File。
   * - ``xhh``
     - ``JsHookRuntime.XhhFacade`` / ``RpcFacade``
     - XiaoHeiHook 版本、grant、JS 引擎能力、对象类型、稳定遍历和 RPC。
   * - ``xhh.global``
     - ``ScriptGlobalState`` / ``JsHookRuntime.GlobalStateFacade``
     - Java-backed 全局状态表，用于跨脚本、跨 Hook/RPC 回调保存运行期对象和值。
   * - ``xhh.fs``
     - ``FileJsApi`` / ``ScriptAssetManager`` / ``TargetAppPathHelper``
     - 文件读写、脚本 assets 读取、资源复制到目标 App 私有目录。
   * - ``debuggerx``
     - ``JsHookRuntime.DebuggerFacade``
     - WebIDE 软断点、行断点和调试日志。
   * - ``dex``
     - ``DexApiFacade``
     - Dex dump、搜索、运行时来源和内存扫描。需要 Dex grant 才注入。
   * - ``require``
     - ``JsHookRuntime.CommonJsRequire``
     - CommonJS 风格相对路径模块加载。

JS 脚本语法边界说明
--------------------------------------------------------------------------------

.. warning::

   当前 Rhino 可以解析 ``const``，但在部分 Android / LSPosed 环境下不能可靠实现
   ``for`` 循环体内 ``const`` 的每轮重新绑定。典型现象是：
   ``for (...) { const path = paths[i]; }`` 第二轮仍读到第一轮的 ``path``。

推荐通用语法：

* Rhino 官方语法兼容性参考页：`Rhino engine compatibility table <https://mozilla.github.io/rhino/compat/engines.html>`_。
* 全局常量、固定配置可以继续使用 ``const TAG = 'Demo'``。
* 循环内会随下标变化的临时变量推荐使用 ``let``。
* 顶层 ``let`` / ``var`` / 普通对象字段可以在 Hook 回调、SAM 回调和 RPC 回调之间保持状态。
* 遍历 ``paths``、``results``、``methods`` 等 JS 数组时，推荐使用 ``xhh.each(array, callback)``。
* 需要跨脚本或跨回调保存 Java ``Method``、``ClassLoader``、``thisObject`` 等运行期对象时，使用 ``xhh.global``。
* 需要确认当前引擎能力时使用 ``xhh.jsEngine()``。
* 需要确认一个值是 JS 对象还是 Java bridge 对象时使用 ``xhh.objectKind(value)``。

**1.32 (109) 推荐写法：**

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
   :no-index:

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

源码定义：``top.lovepikachu.XiaoHeiHook.script.JavaBridge``。

``Java`` 全局对象由 ``JsHookRuntime`` 注入；类 wrapper、实例 wrapper、参数转换和
接口代理分别由 ``JavaClassWrapper``、``JavaObjectWrapper``、``JavaReflectionInvoker``
和 ``JavaProxyFactory`` 实现。

.. function:: Java.type(className)

   加载 Java 类，并返回 JS 友好的 ``JavaClassWrapper``。

   :param string className: Java 类名，例如 ``android.app.Application``。
   :return: ``JavaClassWrapper``，不是裸 ``java.lang.Class``。

.. function:: Java.use(className)

   ``Java.type`` 的别名。

.. function:: Java.classForName(className)

   使用默认 ClassLoader 加载类。``className`` 也可以是 ``int``、``long``、``boolean`` 等基础类型名称。

.. function:: Java.classForName(className, initialize, loader)
   :no-index:

   使用指定初始化参数和 ClassLoader 加载类。

.. function:: Java.method(clazzOrName, methodName)

   获取无参方法。

.. function:: Java.method(clazzOrName, methodName, parameterTypes)
   :no-index:

   获取指定参数列表的方法。

   :param clazzOrName: Java ``Class`` 对象、``JavaClassWrapper`` 或类名字符串。
   :param string methodName: 方法名。
   :param parameterTypes: JS 数组、Java Class 数组、``JavaClassWrapper`` 数组或类名数组；类名数组中可直接写 ``"java.lang.String"``、``"int"`` 等。
   :return: Java ``Method`` 对象。

.. function:: Java.constructor(clazzOrName)

   获取无参构造器。

.. function:: Java.constructor(clazzOrName, parameterTypes)
   :no-index:

   获取指定参数构造器。

.. function:: Java.field(clazzOrName, fieldName)

   获取字段对象。

.. function:: Java.callStatic(clazzOrName, methodName)

   反射调用静态方法。

.. function:: Java.callStatic(clazzOrName, methodName, args)
   :no-index:

   反射调用静态方法，并传入参数数组。

.. function:: Java.callStatic(clazzOrName, methodName, args, parameterTypes)
   :no-index:

   反射调用静态方法，并显式指定参数类型。

.. function:: Java.call(target, methodName)

   反射调用实例方法；如果 ``target`` 是类对象，则按静态方法调用。

.. function:: Java.call(target, methodName, args)
   :no-index:

   反射调用实例方法，并传入参数数组。

.. function:: Java.call(target, methodName, args, parameterTypes)
   :no-index:

   反射调用实例方法，并显式指定参数类型。

.. function:: Java.newInstance(clazzOrName)

   反射调用无参构造器创建实例。

.. function:: Java.newInstance(clazzOrName, args)
   :no-index:

   反射调用构造器创建实例，并传入参数数组。

.. function:: Java.newInstance(clazzOrName, args, parameterTypes)
   :no-index:

   反射调用构造器创建实例，并显式指定参数类型。

.. function:: Java.getStatic(clazzOrName, fieldName)

   读取静态字段。

.. function:: Java.setStatic(clazzOrName, fieldName, value)

   写入静态字段。

.. function:: Java.get(target, fieldName)

   读取实例字段；如果 ``target`` 是类对象，则按静态字段读取。

.. function:: Java.set(target, fieldName, value)

   写入实例字段；如果 ``target`` 是类对象，则按静态字段写入。

.. function:: Java.newArray(componentType, length)

   创建 Java 数组。

.. function:: Java.to(type, value[, options])

   从 ``1.32 (109)`` 起提供。将 JS 值显式转换为指定 Java 类型，并返回可直接传给
   Java 方法、构造函数、``Method.invoke`` 或 ``chain.proceed`` 的 Java 值或 Java wrapper。

   常见用途包括构造 ``Integer`` / ``Long`` / ``BigInteger`` / ``byte[]`` / ``ArrayList`` /
   ``HashMap``，以及在目标参数是 ``Object`` 时指定真实 Java 类型。完整规则见
   :doc:`type_conversion`。

.. function:: Java.proxy(interfaceName, implementation)

   创建 Java 接口代理。``implementation`` 可以是 JS 函数，也可以是包含同名方法的 JS 对象。

   :param string interfaceName: Java 接口名，例如 ``java.lang.Runnable``。
   :param implementation: JS ``function`` 或对象字面量。
   :return: Java ``Proxy`` 对象，可传给需要该接口的 Java 方法。

Java Bridge wrapper 语法
--------------------------------------------------------------------------------

``Java.use(className)`` / ``Java.type(className)`` 返回的是 ``JavaClassWrapper``。它内部持有原始
``java.lang.Class``，但脚本侧可以直接使用更接近 Frida / Rhino 的写法读取静态字段、
调用静态方法、调用构造函数和实例方法。

**1.32 (109) 推荐写法：**

.. code-block:: javascript

   const Toast = Java.use("android.widget.Toast");
   const Looper = Java.use("android.os.Looper");
   const Handler = Java.use("android.os.Handler");

   const mainHandler = new Handler(Looper.getMainLooper());

   mainHandler.post(function () {
       Toast.makeText(context, String(text), Toast.LENGTH_SHORT).show();
   });

.. list-table:: wrapper 行为
   :header-rows: 1
   :widths: 34 66

   * - 写法
     - 行为
   * - ``Toast.LENGTH_SHORT``
     - 读取 Java 静态字段。
   * - ``Looper.getMainLooper()``
     - 调用 Java 静态方法。
   * - ``new Handler(mainLooper)``
     - 调用 Java 构造函数，返回 ``JavaObjectWrapper``。
   * - ``handler.post(function () {})``
     - 调用 Java 实例方法；当目标参数是 ``Runnable`` 等 SAM 接口时自动创建代理。
   * - ``clazz.classObject`` / ``clazz.getRawClass()``
     - 获取原始 ``java.lang.Class``。

``JavaObjectWrapper`` 表示普通 Java 实例。它支持实例字段读取/写入、实例方法调用，
传回 Java 方法或 Xposed API 时会自动解包为原始 Java 对象。

.. code-block:: javascript

   const StringBuilder = Java.use("java.lang.StringBuilder");

   const sb = new StringBuilder();
   sb.append("bridge").append("-").append(106);

   xposed.i("XHH", String(sb.toString()));

如果需要原始对象，可以使用 ``obj.raw`` 或 ``obj.getRawObject()``。普通脚本通常不需要手动解包。

Java Bridge 特殊包装方法
--------------------------------------------------------------------------------

本节只列出 XiaoHeiHook 在 Java Bridge wrapper 上做过特殊适配的方法。普通 Java 静态方法、
实例方法和字段仍按上一节的 wrapper 规则直接访问。以下能力从 ``1.32 (109)`` 起可用。

.. note::

   如果参数已经是 Java 对象、Java bridge wrapper 或 Rhino ``NativeJavaObject``，
   Bridge 只会解包并传回 Java，不会再把它当成 JS 原生值做数字、字符串、数组
   或 Map 的自动转换。需要精确构造 ``Integer``、``Long``、``BigInteger``、
   ``byte[]`` 等 Java 值时，请使用 ``Java.to(...)``。

.. function:: JavaClassWrapper.getDeclaredMethod(name, ...parameterTypes)

   调用原生 ``java.lang.Class#getDeclaredMethod``，但签名参数经过 Bridge 适配。

   :param string name: 方法名。
   :param parameterTypes: 可传多个签名参数，也可传一个数组。元素可以是类名字符串、基础类型名称、
      ``JavaClassWrapper``、原始 ``java.lang.Class`` 或 ``loader.loadClass(name)`` 的返回值。
   :return: ``java.lang.reflect.Method`` 的 Java wrapper。

   **示例：**

   .. code-block:: javascript

      const method = TargetClass.getDeclaredMethod(
          "decrypt",
          "java.lang.String",
          "java.lang.String",
          "int"
      );

      const method2 = TargetClass.getDeclaredMethod("decrypt", [
          "java.lang.String",
          "java.lang.String",
          "int"
      ]);

   ``"int"``、``"long"``、``"boolean"``、``"byte"``、``"char"``、``"short"``、
   ``"float"``、``"double"``、``"void"`` 会解析为对应基础类型 ``Class``。

   .. tip::

      完整反射能力检查脚本见仓库 ``examples/java_reflection_smoke_test.js``。

.. function:: JavaClassWrapper.getMethod(name, ...parameterTypes)

   调用原生 ``java.lang.Class#getMethod``，用于查找 public 方法。签名参数支持规则与
   ``getDeclaredMethod`` 相同。

   :param string name: 方法名。
   :param parameterTypes: 可变签名参数或签名参数数组。
   :return: ``java.lang.reflect.Method`` 的 Java wrapper。

.. function:: JavaClassWrapper.getDeclaredConstructor(...parameterTypes)

   调用原生 ``java.lang.Class#getDeclaredConstructor``，但构造函数签名参数支持字符串快捷写法。

   :param parameterTypes: 可变签名参数或签名参数数组。
   :return: ``java.lang.reflect.Constructor`` 的 Java wrapper。

   **示例：**

   .. code-block:: javascript

      const ctor = TargetClass.getDeclaredConstructor(
          "java.lang.String",
          "int"
      );

.. function:: JavaClassWrapper.getConstructor(...parameterTypes)

   调用原生 ``java.lang.Class#getConstructor``，用于查找 public 构造函数。签名参数支持规则与
   ``getDeclaredConstructor`` 相同。

   :param parameterTypes: 可变签名参数或签名参数数组。
   :return: ``java.lang.reflect.Constructor`` 的 Java wrapper。

.. function:: Method.invoke(receiver, ...args)

   当 ``Method`` 是通过 Java Bridge 取得的 ``java.lang.reflect.Method`` wrapper 时，
   ``invoke`` 会按该 ``Method`` 代表的真实目标方法签名转换 ``args``，而不是只按
   ``Method.invoke(Object, Object...)`` 的 ``Object`` 参数处理。

   :param receiver: 实例方法的 ``this`` 对象；静态方法传 ``null``。
   :param args: 目标方法参数。普通 JS 值会按真实目标参数类型自动转换；已经是 Java 对象
      或 Java wrapper 的值只会解包并原样传回 Java。
   :return: 目标方法返回值；``void`` 返回 ``undefined``。

   **示例：**

   .. code-block:: javascript

      const result = method.invoke(
          null,
          Java.to("java.lang.String", params.data),
          Java.to("java.lang.String", params.key),
          Java.to("int", params.mode || 0)
      );

   当目标参数类型是 ``Object``，或需要 ``Long``、``BigInteger`` 等精确 Java 类型时，
   仍建议显式使用 ``Java.to(...)``。

Java Bridge 自动代理
--------------------------------------------------------------------------------

当 Java 方法参数类型是接口，并且该接口只有一个抽象方法时，可以直接传入 JS 函数。
Bridge 会自动创建 Java ``Proxy``，并在 Java 回调进入时重新进入 Rhino ``Context`` 调用 JS 函数。

.. code-block:: javascript

   const Handler = Java.use("android.os.Handler");
   const Looper = Java.use("android.os.Looper");

   const handler = new Handler(Looper.getMainLooper());

   handler.post(function () {
       xposed.i("XHH", "run on main thread");
   });

多方法接口或需要显式声明方法名时，使用 ``Java.proxy``：

.. code-block:: javascript

   const runnable = Java.proxy("java.lang.Runnable", {
       run: function () {
           xposed.i("XHH", "explicit proxy");
       }
   });

   handler.post(runnable);

单方法接口也可以直接把函数传给 ``Java.proxy``：

.. code-block:: javascript

   const runnable = Java.proxy("java.lang.Runnable", function () {
       xposed.i("XHH", "run");
   });

``equals``、``hashCode``、``toString`` 会由代理默认处理。接口方法返回值会按目标 Java
返回类型转换；JS 异常会沿 Java 调用链抛出。

.. tip::

   完整代理能力检查脚本见仓库 ``examples/java_proxy_smoke_test.js``；综合 Bridge 检查脚本见
   ``examples/java_bridge_smoke_test.js``。

Java Bridge 低层反射 fallback
--------------------------------------------------------------------------------

推荐优先使用 wrapper 写法。低层反射接口仍然保留，适合动态类名、动态方法名、复杂重载、
调试或兼容旧脚本。

.. code-block:: javascript

   const mainLooper = Java.callStatic("android.os.Looper", "getMainLooper");
   const handler = Java.newInstance("android.os.Handler", [mainLooper]);

   Java.call(handler, "post", [
       Java.proxy("java.lang.Runnable", function () {
           xposed.i("XHH", "fallback reflection");
       })
   ], ["java.lang.Runnable"]);

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
   :no-index:

   使用新的参数数组执行原始方法。

.. function:: chain.proceedWith(thisObject)

   使用指定 ``this`` 执行原始方法。

.. function:: chain.proceedWith(thisObject, args)
   :no-index:

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
   :no-index:

   调用方法。

.. function:: invoker.invokeSpecial(thisObject)
.. function:: invoker.invokeSpecial(thisObject, args)
   :no-index:

   special 调用方法。

.. function:: invoker.newInstance()
.. function:: invoker.newInstance(args)
   :no-index:

   调用构造器创建实例。

.. function:: invoker.newInstanceSpecial(subClass)
.. function:: invoker.newInstanceSpecial(subClass, args)
   :no-index:

   special 构造。

``xposed`` 日志接口
--------------------------------------------------------------------------------

.. function:: xposed.log(priority, tag, msg)

   按 Android 日志优先级输出。

.. function:: xposed.log(priority, tag, msg, throwable)
   :no-index:

   输出日志并附带异常；非 Java ``Throwable`` 会安全转成字符串。

.. function:: xposed.v(tag, msg)
.. function:: xposed.d(tag, msg)
.. function:: xposed.i(tag, msg)
.. function:: xposed.w(tag, msg)
.. function:: xposed.e(tag, msg)
.. function:: xposed.e(tag, msg, any)
   :no-index:
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
   :no-index:

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
   :no-index:

   反射调用方法；返回 Map/List 时会稳定化。

.. function:: xposed.raw.field(targetOrClass, fieldName)
.. function:: xposed.raw.method(clazzOrName, methodName)
.. function:: xposed.raw.method(clazzOrName, methodName, parameterTypes)
   :no-index:
.. function:: xposed.raw.constructor(clazzOrName)
.. function:: xposed.raw.constructor(clazzOrName, parameterTypes)
   :no-index:

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


``xhh.global`` 全局状态接口
--------------------------------------------------------------------------------

源码定义：``top.lovepikachu.XiaoHeiHook.script.ScriptGlobalState``，并由
``JsHookRuntime.GlobalStateFacade`` 挂载到 ``xhh.global``。

``xhh.global`` 是 ``1.31 (108)`` 新增的 Java-backed 全局状态表。它和普通 JS 全局变量不同：

* 普通 JS 顶层变量属于当前脚本 runtime，适合脚本内部状态。
* ``xhh.global`` 保存在 Java 层，同一目标进程内的多个脚本 runtime 可以共享。
* ``xhh.global`` 可以保存 Java bridge 对象，例如 ``Method``、``ClassLoader``、``thisObject``。
* 目标 App 进程被杀死后，``xhh.global`` 会随进程一起清空。

.. note::

   ``xhh.global`` 是运行期状态表，不是持久化配置。需要跨进程重启保存的数据，应使用
   ``settings``、脚本配置或 ``xhh.fs.writeText`` 写入文件。

.. function:: xhh.global.set(key, value)

   保存一个全局值。

   :param string key: 状态键。建议使用带命名空间的键，例如 ``decrypt.method``。
   :param value: 任意 JS 值或 Java bridge 对象。
   :return: 保存后的值。

.. function:: xhh.global.get(key)

   读取一个全局值。

   :param string key: 状态键。
   :return: 已保存的值；不存在时返回 ``null``。

.. function:: xhh.global.has(key)

   判断指定键是否存在。

   :param string key: 状态键。
   :return: 布尔值。

.. function:: xhh.global.remove(key)

   移除指定键。

   :param string key: 状态键。
   :return: 被移除的旧值；不存在时返回 ``null``。

.. function:: xhh.global.clear()

   清空当前进程内的全局状态表。

   :return: ``true``。

.. warning::

   ``clear`` 会清空同一目标进程内所有脚本共享的 ``xhh.global`` 状态。普通脚本更推荐
   使用 ``remove`` 删除自己的命名空间键。

.. function:: xhh.global.keys()

   返回当前全局状态表中的全部键名。

   :return: JS 字符串数组。

.. function:: xhh.global.size()

   返回当前全局状态表中的键数量。

   :return: 数字。

.. function:: xhh.global.snapshot()

   返回一个 JS 对象快照，便于日志排查。

   :return: JS 对象。保存的 Java 对象会以 Java bridge 对象形式返回，不会被序列化成字符串。

**示例：Hook 中保存 Method，RPC 中远程调用：**

.. code-block:: javascript

   xposed.hook(targetMethod).intercept(function (chain) {
       const method = chain.getExecutable();
       method.setAccessible(true);

       xhh.global.set('decrypt.method', method);
       xhh.global.set('decrypt.this', chain.getThisObject());

       return chain.proceed();
   });

   xhh.rpc.register_method('decrypt_cbc', function (params) {
       const method = xhh.global.get('decrypt.method');
       const thisObject = xhh.global.get('decrypt.this');

       if (!method) {
           return { ok: false, error: 'method not captured yet' };
       }

       const result = method.invoke(
           thisObject,
           params.data,
           params.key,
           params.iv,
           params.mode || 0
       );

       return {
           ok: true,
           result: result == null ? null : '' + result
       };
   });


``xhh.fs`` 文件与资源接口
--------------------------------------------------------------------------------

源码定义：``top.lovepikachu.XiaoHeiHook.script.FileJsApi``。
底层路径解析与资源复制分别由 ``ScriptPathResolver``、``ScriptAssetManager``、
``TargetAppPathHelper`` 和 ``FileCopyUtils`` 实现。

``xhh.fs`` 用于目标 App 私有目录文件读写、当前脚本 ``assets/`` 资源读取、
以及把脚本资源复制到目标 App ``filesDir`` 下的安全子目录。完整参数、返回值、示例和常见坑见
:doc:`file_api`。

.. list-table:: 常用入口
   :header-rows: 1
   :widths: 36 64

   * - 方法
     - 说明
   * - ``xhh.fs.appDirs(context)``
     - 获取目标 App 私有目录信息。目录来自目标 App ``Context``，不要硬编码 ``/data/user/0``。
   * - ``xhh.fs.join(...parts)``
     - 拼接路径片段。
   * - ``xhh.fs.exists(path)`` / ``isFile(path)`` / ``isDirectory(path)``
     - 判断路径状态。
   * - ``xhh.fs.mkdirs(path)``
     - 创建目录及父目录。
   * - ``xhh.fs.readText(path, charset, options)`` / ``writeText(path, text, charset)``
     - 读取或写入文本文件。读取默认最大 ``16MB``，可通过 ``{ maxBytes }`` 调整。
   * - ``xhh.fs.readBytes(path, options)`` / ``writeBytes(path, bytes)``
     - 读取或写入二进制文件。
   * - ``xhh.fs.copy(src, dst, options)``
     - 复制普通文件，返回 ``{ source, path, bytes, copied, skipped, overwritten }``。
   * - ``xhh.fs.scriptRoot()`` / ``scriptDir()`` / ``assetsDir()``
     - 返回脚本根目录、当前脚本目录、当前脚本 ``assets/`` 目录。
   * - ``xhh.fs.assetPath(relativePath)`` / ``readAssetText(...)`` / ``readAssetBytes(...)``
     - 解析或读取当前脚本 ``assets/`` 下的资源。
   * - ``xhh.fs.appAssetDir(context, options)``
     - 返回当前脚本在目标 App 私有目录中的资源目标根目录。
   * - ``xhh.fs.copyAssetToApp(context, asset, target, options)``
     - 复制单个 asset 到目标 App ``filesDir`` 下的安全子目录，并返回完整目标路径。
   * - ``xhh.fs.syncAssetsToApp(context, options)``
     - 递归同步当前脚本 ``assets/`` 到目标 App 私有目录。

.. tip::

   资源给目标 App 使用时，优先使用 ``copyAssetToApp`` 或 ``syncAssetsToApp``。
   当前版本只支持复制到目标 App ``filesDir`` 下，不支持 ``cacheDir``。

**示例：**

.. code-block:: javascript

   const config = JSON.parse(xhh.fs.readAssetText('data/config.json', 'UTF-8'));
   const copied = xhh.fs.copyAssetToApp(context, 'images/icon.png', 'images/icon.png', {
       rootDir: 'xhh_showcase/demo.multi.asset.showcase',
       overwrite: true
   });
   xposed.i(TAG, 'icon=' + copied.path);

``xhh.rpc`` RPC 接口
--------------------------------------------------------------------------------

源码定义：``JsHookRuntime.RpcFacade``。用于 MCP/外部客户端调用脚本方法。

.. function:: xhh.rpc.register_method(name, handler)

   注册 RPC 方法。

.. function:: xhh.rpc.register_method(name, options, handler)
   :no-index:

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
   :no-index:

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