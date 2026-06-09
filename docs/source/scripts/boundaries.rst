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

``xhh.global``
   进程内全局状态表。从 ``1.31 (108)`` 起提供，用于跨脚本、跨 Hook/RPC 回调保存运行期对象和值。

   常用能力：

   - ``xhh.global.set(key, value)``
   - ``xhh.global.get(key)``
   - ``xhh.global.has(key)``
   - ``xhh.global.remove(key)``
   - ``xhh.global.keys()``
   - ``xhh.global.size()``

   示例::

      xhh.global.set("demo.method", chain.getExecutable());
      const method = xhh.global.get("demo.method");

``xhh.fs``
   文件与资源桥接接口。从 ``1.30 (107)`` 起提供目标 App 私有目录查询、普通文件读写、
   当前脚本 ``assets/`` 读取和资源复制能力。

   常用能力：

   - ``xhh.fs.appDirs(context)``
   - ``xhh.fs.readText(path)`` / ``xhh.fs.writeText(path, text)``
   - ``xhh.fs.readAssetText(relativePath)``
   - ``xhh.fs.copyAssetToApp(context, assetRelativePath, targetRelativePath, options)``
   - ``xhh.fs.syncAssetsToApp(context, options)``

   示例::

      const dirs = xhh.fs.appDirs(context);
      xposed.i("XHH", "filesDir=" + dirs.filesDir);

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


JS 全局作用域与 xhh.global 边界
--------------------------------------

``1.31 (108)`` 后，普通脚本里的顶层变量会更接近正常 JavaScript 语义。Hook 回调、Java SAM
回调和 MCP/RPC 回调会回到函数创建时的脚本作用域中执行，因此同一脚本内可以这样保存状态：

.. code-block:: javascript

   let installed = false;
   var counter = 0;
   const state = { lastArgs: null };

   xposed.hook(method).intercept(function (chain) {
       counter++;
       state.lastArgs = chain.getArgs();
       return chain.proceed();
   });

但是，普通 JS 全局变量只属于当前脚本 runtime，不适合当作跨脚本共享表。需要跨脚本、跨回调
保存运行期对象时，使用 ``xhh.global``：

.. code-block:: javascript

   xhh.global.set("decrypt.method", chain.getExecutable());
   xhh.global.set("decrypt.this", chain.getThisObject());

   const method = xhh.global.get("decrypt.method");
   const thisObject = xhh.global.get("decrypt.this");

.. note::

   ``xhh.global`` 可以保存 Java ``Method``、``Constructor``、``ClassLoader``、``thisObject`` 等
   Java bridge 对象。它不做持久化，目标进程结束后会清空。

.. warning::

   ``xhh.global.clear()`` 会清空同一目标进程内所有脚本共享的状态。为避免误删其他脚本数据，
   建议使用带命名空间的 key，例如 ``demo.scope.method``，并用 ``remove`` 精确删除。

常见坑：

* 不要把 ``xhh.global`` 当作设置系统使用；持久配置应使用脚本设置或文件。
* 不要保存 Activity、View 等生命周期很短的对象后长期复用；对象失效后远程调用可能抛异常。
* 不要用过于通用的 key，例如 ``method``、``context``；多个脚本之间容易覆盖。
* 目标 App 重启后，``xhh.global`` 里的值需要重新通过 Hook 捕获。

文件路径与 assets 边界
--------------------------------------

``xhh.fs`` 解决的是“脚本资源”和“目标 App 私有目录”之间的桥接问题，但它不是
Node.js 的 ``fs``，也不是一个任意路径沙箱逃逸工具。初学者最容易踩坑的是把三类路径混在一起：

1. 脚本根目录，例如当前用户 ``Documents/XiaoHeiHook``。
2. 当前脚本 ``assets/`` 资源目录。
3. 目标 App 自己的 ``filesDir``、``dataDir`` 等私有目录。

.. important::
   不要硬编码 ``/data/user/0``、``/data/data`` 或 ``/sdcard``。目标 App 的真实目录应通过
   ``xhh.fs.appDirs(context)`` 或 Android ``Context`` 动态获取。多用户、工作资料夹、分身应用
   和部分系统环境下，真实 userId 不一定是 ``0``。

脚本 assets 只能访问当前脚本自己的 ``assets/``
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

``readAssetText``、``readAssetBytes``、``assetPath``、``copyAssetToApp`` 和
``syncAssetsToApp`` 都只面向当前脚本的 ``assets/`` 目录。

允许::

   xhh.fs.readAssetText("data/config.json", "UTF-8");
   xhh.fs.copyAssetToApp(context, "images/icon.png", "images/icon.png");

不允许::

   xhh.fs.readAssetText("../index.js");
   xhh.fs.copyAssetToApp(context, "../main.js");
   xhh.fs.copyAssetToApp(context, "/sdcard/Download/a.png");

.. note::
   ``assets/`` 是资源目录，不是 JS 模块目录。需要 ``require`` 的文件放在脚本目录、
   ``lib/`` 或其他普通子目录中；图片、HTML、CSS、JSON 等资源放在 ``assets/``。

资源复制目标必须在目标 App filesDir 内
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

``copyAssetToApp`` 和 ``syncAssetsToApp`` 第一版只支持复制到目标 App ``filesDir`` 下，
不支持 ``cacheDir``，也不会把资源复制到外部存储。默认目录为::

   <context.getFilesDir()>/xhh_assets/<scriptId>/<version>/

如果传入 ``rootDir``，它可以是相对路径，也可以是位于 ``filesDir`` 内的绝对路径，但最终仍必须在
目标 App ``filesDir`` 内。例如：

推荐::

   xhh.fs.copyAssetToApp(context, "images/icon.png", "images/icon.png", {
       rootDir: "xhh_showcase/demo.multi.asset.showcase",
       overwrite: true
   });

不推荐或会失败::

   xhh.fs.copyAssetToApp(context, "images/icon.png", "../icon.png");
   xhh.fs.copyAssetToApp(context, "images/icon.png", "images/icon.png", {
       rootDir: "/sdcard/XiaoHeiHook/out"
   });

.. tip::
   一定要使用返回值里的 ``path``。这个路径是复制完成后的完整路径，适合传给
   ``ImageView``、``WebView.loadUrl('file://' + path)`` 或目标 App 的 Java API。

普通文件 API 与 assets API 的区别
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

``readText``、``writeText``、``readBytes``、``writeBytes``、``copy`` 是普通文件 API。
它们用于脚本已经明确拿到路径的场景，例如写入目标 App ``filesDir`` 下的日志或配置。

``readAssetText``、``copyAssetToApp``、``syncAssetsToApp`` 是 assets API。
它们会额外检查资源路径，防止读取或复制 ``assets/`` 之外的脚本源码和其他文件。

.. warning::
   如果你的目的是“把脚本附带的图片、HTML、JSON 给目标 App 使用”，不要用普通
   ``copy`` 自己拼源路径。直接使用 ``copyAssetToApp`` 或 ``syncAssetsToApp``。

读取大小限制
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

文本和二进制读取默认限制为 ``16MB``。这是为了避免目标 App 启动阶段因为脚本一次性读取大文件而卡顿或 OOM。
需要临时放宽时，可以传 ``maxBytes``：

.. code-block:: javascript

   const text = xhh.fs.readAssetText("data/big.json", "UTF-8", {
       maxBytes: 32 * 1024 * 1024
   });

.. tip::
   启动阶段尽量只读取小配置和小资源。大文件、全量扫描、复杂解析应该延后执行，并且只执行一次。

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

- 如果 Java 方法参数需要 ``Runnable``、listener 或 callback，优先传入 JS 函数，让 Java Bridge 自动创建 SAM 代理。
- 多方法接口使用 ``Java.proxy(interfaceName, { methodName: function () {} })`` 显式声明。
- 长耗时逻辑仍应通过 Xposed Hook 生命周期或目标 App 生命周期事件触发，不要在启动链路里阻塞等待。

不要直接 new Java 接口 / 避免阻塞式调度
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

不要在 JS 里尝试直接构造 Java 接口实现::

   const Runnable = Java.type("java.lang.Runnable");
   new Runnable(...);

Java 接口不能直接 ``new``。如果 Java 方法参数需要 ``Runnable`` 这类单方法接口，
推荐直接传入 JS 函数，Bridge 会自动转换为 Java 代理：

.. code-block:: javascript

   const Handler = Java.type("android.os.Handler");
   const Looper = Java.type("android.os.Looper");

   const handler = new Handler(Looper.getMainLooper());

   handler.post(function () {
     xposed.i("TAG", "posted");
   });

多方法接口或需要显式声明方法名时，使用 ``Java.proxy``：

.. code-block:: javascript

   const runnable = Java.proxy("java.lang.Runnable", {
     run: function () {
       xposed.i("TAG", "run");
     }
   });

   handler.post(runnable);

仍然不建议在启动链路中随意创建线程、长时间延迟或阻塞等待。推荐用生命周期触发::

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

Java Bridge wrapper 边界
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

``Java.type(className)`` 返回 ``JavaClassWrapper``，不是裸 ``java.lang.Class``。
它可以直接读取静态字段、调用静态方法，也可以通过 ``new`` 调用构造函数：

.. code-block:: javascript

   const Looper = Java.type("android.os.Looper");
   const Handler = Java.type("android.os.Handler");

   const handler = new Handler(Looper.getMainLooper());

``JavaClassWrapper`` 传入 Java 反射或 Android API 时会自动解包为原始
``java.lang.Class``。因此可以直接写：

.. code-block:: javascript

   const Application = Java.type("android.app.Application");
   const ContextClass = Java.type("android.content.Context");

   const attach = Application.getDeclaredMethod("attach", ContextClass);
   attach.setAccessible(true);

需要原始 ``Class`` 时，可使用：

.. code-block:: javascript

   const raw = Application.classObject || Application.getRawClass();
   xposed.i("TAG", raw.getName());

普通 Java 实例会以 ``JavaObjectWrapper`` 形式暴露。它支持实例方法和字段访问，
传回 Java 方法、``xposed.hook``、``chain.proceed`` 等接口时会自动解包。

.. code-block:: javascript

   const StringBuilder = Java.type("java.lang.StringBuilder");

   const sb = new StringBuilder();
   sb.append("hello").append(" world");

   xposed.i("TAG", String(sb.toString()));

常见边界转换规则：

.. list-table:: Java Bridge 边界转换
   :header-rows: 1
   :widths: 35 65

   * - JS 侧值
     - 传入 Java 时
   * - ``JavaClassWrapper``
     - 解包为原始 ``java.lang.Class``。
   * - ``JavaObjectWrapper``
     - 解包为原始 Java 对象。
   * - JS ``function``
     - 在 SAM 接口参数位置自动转换为 Java ``Proxy``。
   * - JS object
     - 通过 ``Java.proxy`` 作为接口实现对象时，按 Java 方法名查找同名 JS 函数。
   * - JS number
     - 根据目标参数转换为 ``int``、``long``、``float``、``double`` 等。
   * - JS string
     - 转为 Java ``String``，并兼容 ``CharSequence`` 参数。

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

``RPC 读取不到 Hook 中保存的变量``
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

原因：旧版本中 Hook 回调、Java callback 或 RPC callback 可能没有回到同一个 JS 作用域执行。

解决：

- 升级到 ``1.31 (108)`` 或更新版本。
- 同一脚本内部状态可以使用顶层 ``let``、``var`` 或普通对象字段。
- 跨脚本或需要保存 Java ``Method`` / ``thisObject`` 时，使用 ``xhh.global``。
- 如果值来自某次 Hook，目标 App 重启后需要重新触发 Hook 才能再次捕获。

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
4. 不要使用 ``JavaAdapter`` 或直接 ``new`` Java 接口；需要接口回调时使用自动 SAM 代理或 ``Java.proxy``。
5. 不要在 JS 里手动处理大 dex 文件或大量 Java 文件对象。
6. Dex 搜索和 inspect 优先交给 Java 层 API。
7. 使用 ``executed`` / ``installed`` 防止重复执行。
8. 启动阶段只做轻量操作，重任务要尽量精确、只执行一次。
