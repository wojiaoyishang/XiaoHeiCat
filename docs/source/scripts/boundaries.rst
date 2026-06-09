.. _脚本语法边界:

JS 脚本语法边界说明
================================

本文说明 XiaoHeiHook 模块内 JS 脚本的可用能力、边界、推荐写法和常见踩坑。

运行环境不是浏览器，也不是 Node.js，而是运行在目标 App 进程内的 Rhino JS 引擎，并通过模块暴露的 ``java``、``xposed``、``env``、``dex`` 等对象与 Android / LSPosed / Xposed 交互。

因此，脚本编写时应优先遵循两个原则：

1. **以 Java 反射和 Xposed Hook 为主，不依赖浏览器/Node 能力。**
2. **以目标 App 的真实 ClassLoader 为准，不使用模块自己的 ClassLoader 查找业务类。**

脚本头部 Metadata
-------------------------------

每个脚本建议使用 ``LSPosedScript`` 头部声明元数据。

示例::

   // ==LSPosedScript==
   // @name         七点工具箱 Hook 示例
   // @id           qidian.hook.example
   // @version      1.0.0
   // @description  Hook 指定目标方法，仅用于示例。
   // @target       cn.am7code.tools
   // @process      cn.am7code.tools
   // @run-at       package-loaded
   // @grant        java.full
   // @grant        xposed.full
   // ==/LSPosedScript==

常用字段说明
~~~~~~~~~~~~~~~~~~~~~~~~

``@target``
   目标包名。建议写明确包名，不建议长期使用 ``*``，避免脚本注入到无关 App。

``@process``
   目标进程名。主进程通常与包名相同。多进程 App 应按实际进程分别配置。

``@run-at``
   运行时机。常用值为 ``package-loaded``。

``@grant``
   权限声明。模块会根据 grant 决定是否注入对应能力。
   
.. _权限边界:

Grant 权限边界
----------------------

推荐按需声明 grant，不要给所有脚本默认添加全部能力。

``java.full``
   允许使用 ``Java.type(...)`` 访问 Java 类、反射、文件、集合等。

``xposed.full``
   允许使用 ``xposed.onPackageLoaded``、``xposed.hook`` 等 Hook 能力。

``dex.dump``
   允许注入 DumpDex / Dex 分析能力。

   只有确实需要脱壳、dump dex、读取 dex、检查方法体时才声明。

``dex.read`` / ``dex.search`` / ``dex.full``
   可用于只读 Dex 分析或完整 Dex 能力。实际项目中建议优先使用更明确的 ``dex.dump``。

``rpc.register``
   允许脚本声明会通过 ``xhh.rpc.register_method`` 注册 MCP 远程方法。从 ``1.20 (102)`` 起用于 MCP 远程调用脚本。

``mcp.debug``
   开启 MCP bridge 内部调试日志。只影响日志输出，不授予远程调用能力。

``dex.debug``
   开启 Dex / DumpDex 内部调试日志。只影响日志输出，不授予 dex dump 能力。

``xhh.debug``
   开启 XiaoHeiHook 内部全局调试日志。通常只在排查问题时临时使用。

.. tip::
   grant 应按需声明。普通 Hook 脚本通常不需要 ``rpc.register``、``dex.dump``、``mcp.debug`` 或 ``dex.debug``。

没有 Dex grant 时
~~~~~~~~~~~~~~~~~~~~~~~

没有 ``dex.dump`` / ``dex.full`` / ``dex.read`` / ``dex.search`` 等 grant 的脚本，不应依赖全局 ``dex`` 对象。

例如普通 Hook 脚本只需要::

   // @grant java.full
   // @grant xposed.full

只有脱壳脚本才需要::

   // @grant dex.dump

可用的全局对象
--------------

``xhh``
   XiaoHeiHook 运行时信息和扩展入口。从 ``1.20 (102)`` 起提供版本信息、grant 判断和远程调用注册对象 ``xhh.rpc``。

   常用能力：

   - ``xhh.info()``
   - ``xhh.hasGrant(name)``
   - ``xhh.rpc.register_method(name, options, handler)``
   - ``xhh.rpc.unregister_method(name)``
   - ``xhh.rpc.unregister_all_methods()``

   示例::

      if (xhh.hasGrant("rpc.register")) {
          console.log("rpc register grant declared");
      }

``env``
   运行环境信息，例如包名、进程名、脚本名等。

   示例::

      console.log("process=" + env.processName);

``xposed``
   LSPosed / Xposed Hook API。

   常用能力：

   - ``xposed.onPackageLoaded(callback)``
   - ``xposed.hook(method).intercept(callback)``
   - ``xposed.i(tag, msg)``
   - ``xposed.d(tag, msg)``
   - ``xposed.w(tag, msg)``
   - ``xposed.e(tag, msg, throwable)``

``Java``
   Java 类访问入口。

   示例::

      const Application = Java.type("android.app.Application");
      const ContextClass = Java.type("android.content.Context");

``dex``
   Dex / DumpDex API。只有脚本声明了对应 grant 时才可用。

   常用能力：

   - ``dex.dumpDexCookies(options)``
   - ``dex.dumpDecryptedDexForMethod(options)``
   - ``dex.dumpAndInspectMethod(options)``
   - ``dex.inspectMethodInFile(options)``
   - ``dex.locateMethodInCookieDumps(options)``

不支持或不建议使用的能力
--------------------------------------

不要把脚本当作浏览器 JS 或 Node.js 使用。

不支持浏览器 API
~~~~~~~~~~~~~~~~~~~~~~~~~~

以下对象通常不可用::

   window
   document
   localStorage
   sessionStorage
   XMLHttpRequest
   fetch
   WebSocket
   Worker
   setTimeout
   setInterval

不支持 Node.js API
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

以下对象通常不可用::

   require
   module
   exports
   process
   Buffer
   fs
   path
   crypto

注意：这里的 ``process`` 是 Node.js 的 ``process``，不要与 ``env.processName`` 混淆。

不建议使用过新的 JS 语法
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Rhino 支持能力与浏览器、Node.js、QuickJS/V8 不同。为了兼容 Android 目标进程、
Xposed Hook 回调和 Java 对象桥接，脚本应优先使用保守、明确、可长期运行的通用语法。

推荐通用语法::

   var / let
   function name() {}
   普通对象: { key: value }
   普通数组: [a, b, c]
   for 循环
   xhh.each(arrayLike, function (item, index) { ... })
   try/catch

``const`` 的推荐使用范围
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

``const`` 可以用于不会重新绑定的固定值，例如脚本标签、目标包名、目标类名、smali 特征数组等。

推荐::

   const TAG = "Demo";
   const TARGET_CLASS = "pc.a";
   const SMALI_FEATURES = [
     "am7_dev_vip_override",
     "Ljava/lang/System;->currentTimeMillis()J"
   ];

但是，不建议在 ``for`` 循环体内用 ``const`` 保存随循环下标变化的值。
当前 Rhino 即使启用了 ES6 解析，也可能不能正确实现循环体 ``const`` 的每轮重新绑定。
典型问题是：数组元素本身不同，但第二轮及后续循环仍然读取第一轮的 ``const`` 绑定值。

不推荐::

   for (let i = 0; i < paths.length; i++) {
     const path = String(paths[i]);
     xposed.i(TAG, "path=" + path);
   }

推荐写法一：循环内变化值使用 ``let``::

   for (let i = 0; i < paths.length; i++) {
     let path = String(paths[i]);
     xposed.i(TAG, "path=" + path);
   }

推荐写法二：使用 ``xhh.each``，让 Java 层逐项调用新的 JS 函数帧::

   xhh.each(paths, function (path, i) {
     xposed.i(TAG, "path[" + i + "]=" + path);
     return true;
   });

可以通过 ``xhh.jsEngine()`` 查看当前运行时能力::

   const engine = xhh.jsEngine();
   xposed.i("XHH", "constLoopLexicalBinding=" + engine.constLoopLexicalBinding);
   xposed.i("XHH", "recommendedLoopBinding=" + engine.recommendedLoopBinding);

当 ``constLoopLexicalBinding`` 为 ``false`` 时，脚本不要依赖 ``for`` 循环体内 ``const``
的每轮重新绑定。若必须使用原生现代 JS 语义，需要在框架层切换 JS 引擎，而不是靠脚本写法修复。

谨慎使用或避免使用::

   async / await
   Promise
   import / export
   class
   generator
   proxy
   decorator
   optional chaining: obj?.field
   nullish coalescing: value ?? fallback
   arrow function: () => {}
   destructuring: const { a } = obj
   template string: `value=${v}`

如果脚本需要长期稳定运行，推荐使用传统函数写法::

   function run() {
     xposed.i("TAG", "hello");
   }

不推荐::

   const run = () => {
     xposed.i("TAG", "hello");
   };

Android Rhino 特别注意事项
--------------------------------------

不要使用 JavaAdapter
~~~~~~~~~~~~~~~~~~~~~~~~~~~

Android 上 Rhino 的 ``JavaAdapter`` 常见问题是无法动态生成/加载 class，可能报错::

   UnsupportedOperationException: can't load this type of class file

不要这样写::

   const Runnable = Java.type("java.lang.Runnable");
   const r = new JavaAdapter(Runnable, {
     run: function () {}
   });

推荐改为：

- 使用 Xposed Hook 的生命周期触发。
- 在 Java 层暴露安全的延迟/调度 API。
- 或者通过目标 App 生命周期事件触发扫描。

不要直接 new Runnable / Handler.postDelayed
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

不要在 JS 里尝试构造 Java 接口实现::

   new Runnable(...)
   new java.lang.Thread(...)
   new Handler().postDelayed(...)

这些写法容易因为接口实现、ClassLoader、线程上下文导致异常。

推荐用生命周期触发::

   Application.attach
   Application.onCreate
   Activity.onCreate
   Activity.onResume
   ClassLoader.loadClass

不要 Thread.sleep 阻塞主线程
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

脚本通常运行在目标 App 主线程或关键启动链路中。不要这样写::

   Java.type("java.lang.Thread").sleep(5000);

这会卡住目标 App，甚至导致 ANR。

如果需要等待某个类加载，推荐 hook ``ClassLoader.loadClass``，见后文示例。

Java 对象互操作边界
-----------------------------------------

Rhino 包装 Java 对象时，经常出现 ``NativeJavaObject``。不要把 Java 对象当成普通 JS 对象随意调用。

常见错误::

   TypeError: org.mozilla.javascript.NativeJavaObject@xxxx 不是函数，它是 object。

JS 对象与 Java 对象判断
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

``1.30 (106)`` 后，XiaoHeiHook 自己的 JS API 会尽量返回 JS 对象或 JS 数组。
因此 ``dex.dumpDexCookies``、``dex.findMethods``、``settings.all``、``xhh.info`` 等结果
应优先使用点号和数组下标读取：

.. code-block:: javascript

   const paths = ret.paths || [];
   const found = inspected.found;
   const first = results[0];

不要再给这些新 API 返回值编写 Java ``Map.get`` / ``List.get`` 兼容分支。
需要确认一个值的类型时，使用：

.. code-block:: javascript

   const kind = xhh.objectKind(value);
   xposed.i('Kind', 'kind=' + kind.kind + ' java=' + kind.isJavaObject);

``xhh.isJsObject(value)`` 可判断是否为 JS 对象或 JS 数组；
``xhh.isJavaObject(value)`` 可判断是否为 Java bridge 对象。

Java Map / List 只用于原始 Java bridge 对象
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

只有当值来自目标 App 或 Android API，且 ``xhh.isJavaObject(value)`` 为 ``true`` 时，
才按 Java 对象处理。例如目标 App 返回的 ``java.util.Map`` 仍然应使用 Java 方法：

.. code-block:: javascript

   if (xhh.isJavaObject(javaMap)) {
       const v = javaMap.get('key');
       xposed.i('Map', 'value=' + v);
   }

XiaoHeiHook 新 API 返回的 ``ret.paths``、``results``、``settings.all()`` 不属于这种情况。

JS 数组读取
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

新 API 返回的数组可以直接使用 ``length`` 和下标，也可以使用 ``xhh.each``：

.. code-block:: javascript

   xhh.each(ret.paths || [], function (path, i) {
       xposed.i('Dump', 'path[' + i + ']=' + path);
       return true;
   });

如果你手里拿到的是目标 App 返回的真实 Java 数组，再使用 ``java.lang.reflect.Array``：

.. code-block:: javascript

   const ArrayReflect = Java.type('java.lang.reflect.Array');
   const n = ArrayReflect.getLength(javaArray);

   for (let i = 0; i < n; i++) {
       let item = ArrayReflect.get(javaArray, i);
       xposed.i('Array', 'item=' + item);
   }

Java 字符串处理
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Java ``String`` 与 JS ``String`` 不是完全相同的对象。调用 JS 字符串方法前建议强制转换::

   const name = String(file.getName());

   if (name.indexOf("cookie_") === 0) {
     // ok
   }

不推荐直接写::

   file.getName().startsWith("cookie_")

ClassLoader 边界
-------------------------

Hook 业务类时，必须使用目标 App 的真实 ClassLoader。

错误思路::

   Java.type("pc.a")

这种方式通常使用模块自身 ClassLoader，无法加载目标 App 的业务类。

正确思路：在 ``Application.attach(Context)`` 后取 ``context.getClassLoader()``。

示例::

   const Application = Java.type("android.app.Application");
   const ContextClass = Java.type("android.content.Context");

   const attach = Application.getDeclaredMethod("attach", ContextClass);
   attach.setAccessible(true);

   xposed
     .hook(attach)
     .setPriority(xposed.PRIORITY_DEFAULT)
     .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
     .intercept(function (chain) {
       const context = chain.getArg(0);
       const result = chain.proceed();

       const loader = context.getClassLoader();
       const TargetClass = loader.loadClass("pc.a");

       xposed.i("TAG", "TargetClass=" + TargetClass);
       return result;
     });

Hook 基本写法
----------------------

Hook Application.attach
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

推荐所有需要真实 ClassLoader 的脚本都从 ``Application.attach`` 进入。

示例::

   const TAG = "Example";
   let executed = false;

   xposed.onPackageLoaded(function (param) {
     const Application = Java.type("android.app.Application");
     const ContextClass = Java.type("android.content.Context");

     const attach = Application.getDeclaredMethod("attach", ContextClass);
     attach.setAccessible(true);

     xposed
       .hook(attach)
       .setPriority(xposed.PRIORITY_DEFAULT)
       .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
       .intercept(function (chain) {
         const context = chain.getArg(0);
         const result = chain.proceed();

         if (executed) return result;
         executed = true;

         const loader = context.getClassLoader();
         xposed.i(TAG, "appClassLoader=" + loader);

         return result;
       });
   });

Hook 目标方法并保持原返回值
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

示例：只观察 ``pc.a.d()Z``，不修改逻辑::

   function installHook(loader) {
     const TargetClass = loader.loadClass("pc.a");
     const method = TargetClass.getDeclaredMethod("d");
     method.setAccessible(true);

     xposed
       .hook(method)
       .setPriority(xposed.PRIORITY_DEFAULT)
       .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
       .intercept(function (chain) {
         xposed.d("Hook", "pc.a.d before");
         const oldResult = chain.proceed();
         xposed.d("Hook", "pc.a.d after result=" + oldResult);
         return oldResult;
       });
   }

Hook 目标方法并修改返回值
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

示例：让 ``pc.a.d()Z`` 始终返回 ``true``::

   function installHook(loader) {
     const TargetClass = loader.loadClass("pc.a");
     const method = TargetClass.getDeclaredMethod("d");
     method.setAccessible(true);

     xposed
       .hook(method)
       .setPriority(xposed.PRIORITY_DEFAULT)
       .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
       .intercept(function (chain) {
         let oldResult = false;

         try {
           oldResult = chain.proceed();
           xposed.d("Hook", "original result=" + oldResult);
         } catch (e) {
           xposed.e("Hook", "original call failed", e);
         }

         xposed.i("Hook", "override result=true");
         return true;
       });
   }

注意：如果你不调用 ``chain.proceed()``，原方法不会执行。

等待类/方法加载
------------------------

如果 ``Application.attach`` 后目标类还不能加载，可以 hook ``ClassLoader.loadClass`` 等待目标类出现。

示例::

   const TARGET_CLASS = "pc.a";
   const TARGET_METHOD = "d";

   let hooked = false;

   function installLoadClassWatcher(context, appLoader) {
     const ClassLoader = Java.type("java.lang.ClassLoader");
     const StringClass = Java.type("java.lang.String");

     const loadClass = ClassLoader.getDeclaredMethod("loadClass", StringClass);
     loadClass.setAccessible(true);

     xposed
       .hook(loadClass)
       .setPriority(xposed.PRIORITY_LOWEST)
       .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
       .intercept(function (chain) {
         const name = String(chain.getArg(0));
         const result = chain.proceed();

         if (!hooked && name === TARGET_CLASS) {
           hooked = true;
           xposed.i("Wait", "target class loaded: " + name);
           installTargetHook(appLoader);
         }

         return result;
       });
   }

   function installTargetHook(loader) {
     const TargetClass = loader.loadClass(TARGET_CLASS);
     const method = TargetClass.getDeclaredMethod(TARGET_METHOD);
     method.setAccessible(true);
     xposed.i("Wait", "method ready=" + method);
   }

DumpDex 边界
------------------

DumpDex 能力必须声明::

   // @grant dex.dump

推荐输出目录::

   /data/user/0/<目标包名>/code_cache/xhh_dumpdex

不建议再使用历史调试目录::

   xhh_class_dex_cookie
   xhh_memory_dex
   xhh_memory_dex_raw
   xhh_unpacked_dex

Dex 文件遍历建议
~~~~~~~~~~~~~~~~~~~~~~~~~~~~

脱壳后遍历 ``ret.paths``、搜索 ``dex.findMethods`` 结果、遍历方法列表时，推荐使用
``let`` 或 ``xhh.each``。不要在这些循环体里用 ``const`` 保存当前 ``path`` 或当前结果项。

推荐::

   xhh.each(paths, function (path, i) {
     const results = dex.findMethods({
       path: path,
       smaliContains: SMALI_FEATURES,
       limit: 20
     });
     xposed.i(TAG, "dex[" + i + "] resultCount=" + results.length);
     return true;
   });

通用只脱壳示例
~~~~~~~~~~~~~~~~~~~~~

该脚本只 dump，不搜索、不 inspect、不 hook 业务方法::

   // ==LSPosedScript==
   // @name         通用只脱壳 DumpDex
   // @id           generic.dumpdex.only
   // @version      1.0.0
   // @target       *
   // @process      *
   // @run-at       package-loaded
   // @grant        java.full
   // @grant        xposed.full
   // @grant        dex.dump
   // ==/LSPosedScript==

   const TAG = "DumpDexOnly";
   let executed = false;

   xposed.onPackageLoaded(function (param) {
     const Application = Java.type("android.app.Application");
     const ContextClass = Java.type("android.content.Context");

     const attach = Application.getDeclaredMethod("attach", ContextClass);
     attach.setAccessible(true);

     xposed
       .hook(attach)
       .setPriority(xposed.PRIORITY_DEFAULT)
       .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
       .intercept(function (chain) {
         const context = chain.getArg(0);
         const result = chain.proceed();

         if (executed) return result;
         executed = true;

         const packageName = String(context.getPackageName());
         const loader = context.getClassLoader();
         const outputDir = "/data/user/0/" + packageName + "/code_cache/xhh_dumpdex";

         xposed.i(TAG, "dump start outputDir=" + outputDir);

         const ret = dex.dumpDexCookies({
           loader: loader,
           outputDir: outputDir,
           cookieDir: outputDir,
           clearOutputDir: true,
           maxDexBytes: 512 * 1024 * 1024
         });

         xposed.i(TAG, "dump finished result=" + ret);
         return result;
       });
   });

等待指定方法加载后脱壳
~~~~~~~~~~~~~~~~~~~~~~~~~~~~

适合壳在启动后才逐步加载业务类的情况::

   const TARGET_CLASS = "pc.a";
   const TARGET_METHOD = "d";

   function tryDumpWhenMethodReady(context, loader) {
     try {
       const clazz = loader.loadClass(TARGET_CLASS);
       const method = clazz.getDeclaredMethod(TARGET_METHOD);
       method.setAccessible(true);

       const packageName = String(context.getPackageName());
       const outputDir = "/data/user/0/" + packageName + "/code_cache/xhh_dumpdex";

       const ret = dex.dumpDexCookies({
         loader: loader,
         outputDir: outputDir,
         cookieDir: outputDir,
         clearOutputDir: true,
         maxDexBytes: 512 * 1024 * 1024
       });

       xposed.i("Dump", "dump finished result=" + ret);
       return true;
     } catch (e) {
       return false;
     }
   }

Inspect / 搜索 Dex 方法体
--------------------------------

如果已经 dump 出 dex，并且想检查某个方法是否包含目标特征，优先使用 Java 层接口，
不要在 JS 里手动遍历 dex 文件。``1.30 (106)`` 后返回值已经是 JS 对象，可直接读取字段。

精确检查 ``pc.a.d()Z``：

.. code-block:: javascript

   const inspected = dex.inspectMethodInFile({
       path: '/data/user/0/cn.am7code.tools/code_cache/xhh_dumpdex/cookie_001.dex',
       className: 'pc.a',
       methodName: 'd',
       proto: '()Z',
       strings: [
           'am7_dev_vip_override',
           'getString(...)',
           'vip',
           'nonvip'
       ],
       invokeContains: [
           'Landroid/content/SharedPreferences;->getString(',
           'Ljava/lang/System;->currentTimeMillis('
       ],
       maxDexBytes: 512 * 1024 * 1024
   });

   xposed.i('Inspect', 'found=' + inspected.found);
   xposed.i('Inspect', 'featuresOk=' + inspected.featuresOk);
   xposed.i('Inspect', 'smaliHead=' + String(inspected.smaliHead || ''));

如果不知道类名和方法名，应使用 ``dex.findMethods`` 做特征搜索：

.. code-block:: javascript

   const results = dex.findMethods({
       path: dexPath,
       smaliContains: [
           'const-string "am7_dev_vip_override"',
           'Ljava/lang/System;->currentTimeMillis()J'
       ],
       limit: 1,
       includeSmali: true
   });

   if (results.length > 0) {
       const m = results[0];
       xposed.i('Find', 'method=' + m.className + '.' + m.methodName + m.proto);
   }

日志规范
-----------

推荐使用固定 TAG::

   const TAG = "MyScript";

不同等级::

   xposed.d(TAG, "debug message");
   xposed.i(TAG, "info message");
   xposed.w(TAG, "warning message");
   xposed.e(TAG, "error message", e);

不要在循环里大量输出完整 smali 或超长数组，容易造成性能问题。

推荐输出摘要::

   xposed.i(TAG, "found=" + found + " path=" + path);
   xposed.i(TAG, "strings=" + strings);
   xposed.i(TAG, "smali.head=" + smaliHead);

性能与稳定性建议
----------------

避免启动阶段重任务
~~~~~~~~~~~~~~~~~~

``Application.attach`` 是启动关键路径。不要在里面做超长扫描、全量反编译、全部 smali 输出。

推荐：

- 只做必要的 ClassLoader 获取。
- 只 dump 一次。
- 使用 ``executed`` 防重复。
- 搜索时优先使用 Java 层精确接口。

避免重复 Hook
~~~~~~~~~~~~~

所有安装 Hook 的脚本都应加状态位::

   let installed = false;

   function installHook(loader) {
     if (installed) return;
     installed = true;
     // install hook
   }

避免全进程注入
~~~~~~~~~~~~~~

除非写通用工具脚本，否则不要长期使用::

   // @target *
   // @process *

推荐绑定目标包名::

   // @target cn.am7code.tools
   // @process cn.am7code.tools

常见错误与解决办法
------------------

``NativeJavaObject 不是函数``
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

原因：把 Java 对象当成 JS 函数或 JS 对象调用。

解决：

- 先用 ``xhh.objectKind(value)`` 判断值是 JS 对象还是 Java bridge 对象。
- XiaoHeiHook 新 API 返回值优先用 ``obj.field``、``arr[i]``、``xhh.each``。
- 目标 App 返回的 Java ``Map`` 才使用 ``map.get("key")``。
- 目标 App 返回的 Java 数组才使用 ``java.lang.reflect.Array``。
- Java String 先 ``String(value)`` 再调用 JS 字符串方法。
- 尽量使用 Java 层 Dex API，避免 JS 手动遍历大量 Java 文件对象。

``JavaAdapter can't load this type of class file``
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

原因：Android Rhino 环境不适合动态生成 Java 接口实现。

解决：不要使用 ``JavaAdapter``、``new Runnable``。改用 Hook 生命周期触发。

``ClassNotFoundException: pc.a``
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

原因：使用了错误 ClassLoader，或业务类尚未加载/解密。

解决：

- 在 ``Application.attach`` 后使用 ``context.getClassLoader()``。
- 如果仍失败，hook ``ClassLoader.loadClass`` 等待目标类。

``dex 未定义``
~~~~~~~~~~~~~~~~~~~

原因：脚本没有声明 Dex grant。

解决：添加::

   // @grant dex.dump

或者按需添加 ``dex.read`` / ``dex.search`` / ``dex.full``。

``OutOfMemoryError``
~~~~~~~~~~~~~~~~~~~~~~~~~

原因：一次性打开太多 dex、输出过多 smali、在 JS 里读大文件。

解决：

- 只打开目标 dex。
- 使用 ``dex.inspectMethodInFile`` 等精确接口。
- 不在 JS 里一次性读几十 MB 文件。
- 不在启动回调里做全量扫描。

推荐脚本结构模板
----------------------

通用结构::

   // ==LSPosedScript==
   // @name         Example
   // @id           example.script
   // @version      1.0.0
   // @target       your.package.name
   // @process      your.package.name
   // @run-at       package-loaded
   // @grant        java.full
   // @grant        xposed.full
   // ==/LSPosedScript==

   const TAG = "Example";
   let executed = false;

   xposed.onPackageLoaded(function (param) {
     const pkg = String(param.getPackageName());
     xposed.i(TAG, "loaded package=" + pkg + " process=" + env.processName);

     const Application = Java.type("android.app.Application");
     const ContextClass = Java.type("android.content.Context");
     const attach = Application.getDeclaredMethod("attach", ContextClass);
     attach.setAccessible(true);

     xposed
       .hook(attach)
       .setPriority(xposed.PRIORITY_DEFAULT)
       .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
       .intercept(function (chain) {
         const context = chain.getArg(0);
         const result = chain.proceed();

         if (executed) return result;
         executed = true;

         try {
           const loader = context.getClassLoader();
           xposed.i(TAG, "appClassLoader=" + loader);

           // your logic here
         } catch (e) {
           xposed.e(TAG, "script logic failed", e);
         }

         return result;
       });
   });

最终建议
-----------

1. 普通 Hook 脚本只声明 ``java.full`` 和 ``xposed.full``。
2. 脱壳脚本额外声明 ``dex.dump``。
3. 业务类必须通过 ``Application.attach`` 后的 ``context.getClassLoader()`` 加载。
4. 不要使用 ``JavaAdapter``、``Runnable``、``Thread.sleep``。
5. 不要在 JS 里手动处理大 dex 文件或大量 Java 文件对象。
6. Dex 搜索和 inspect 优先交给 Java 层 API。
7. 使用 ``executed`` / ``installed`` 防止重复执行。
8. 启动阶段只做轻量操作，重任务要尽量精确、只执行一次。
