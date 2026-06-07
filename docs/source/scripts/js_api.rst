JS API 参考
==================

本页列出 XiaoHeiHook 在 JS 全局作用域中暴露的全部接口。所有示例默认运行在 Rhino JavaScript 环境中。

全局对象总览
------------------

每个脚本加载后，运行时会注入以下全局对象：

.. list-table::
   :header-rows: 1
   :widths: 25 75

   * - 全局对象
     - 用途
   * - ``xposed``
     - 现代 LSPosed/libxposed 能力封装，包括生命周期、Hook、Invoker、日志、Remote Preferences、Remote Files、堆栈等。
   * - ``Java``
     - Java Class 与反射辅助接口，用于获取 ``Class``、``Method``、``Constructor``、``Field``。
   * - ``env``
     - 当前脚本、目标应用、目标进程、ClassLoader 等运行环境信息。
   * - ``settings``
     - 当前应用下该脚本的合并配置值，只读。
   * - ``console``
     - 输出日志到 XiaoHeiHook 终端。
   * - ``require``
     - 多文件脚本的 CommonJS 风格相对路径加载器。
   * - ``debuggerx``
     - WebIDE 软断点和调试事件接口。

.. important::
   这些对象只在脚本运行时存在，不是浏览器或 Node.js 的对象。脚本中没有 DOM，也不能使用 Node.js 内置模块。

xposed 生命周期接口
------------------------

生命周期接口用于接收当前脚本所处的 libxposed 生命周期事件。回调是否执行，取决于脚本元数据中的 ``@run-at`` 与当前事件是否匹配。

.. list-table::
   :header-rows: 1
   :widths: 35 25 40

   * - 接口
     - 返回值
     - 说明
   * - ``xposed.onModuleLoaded(callback)``
     - ``void``
     - 当前事件为 ``module-loaded`` 时执行回调。
   * - ``xposed.onPackageLoaded(callback)``
     - ``void``
     - 当前事件为 ``package-loaded`` 时执行回调。
   * - ``xposed.onPackageReady(callback)``
     - ``void``
     - 当前事件为 ``package-ready`` 时执行回调。
   * - ``xposed.onSystemServerStarting(callback)``
     - ``void``
     - 当前事件为 ``system-server-starting`` 时执行回调。

参数：

``callback``
   类型为 ``function(param)``。``param`` 是生命周期参数对象，见 :ref:`lifecycle-param-api`。

示例：

.. code-block:: javascript

   xposed.onPackageLoaded(function (param) {
       xposed.i('XHH', 'package=' + param.getPackageName());
       xposed.i('XHH', 'process=' + param.getProcessName());
   });

.. _lifecycle-param-api:

LifecycleParam
~~~~~~~~~~~~~~~~~~

生命周期回调的 ``param`` 对象提供以下接口：

.. list-table::
   :header-rows: 1
   :widths: 35 25 40

   * - 属性 / 方法
     - 返回值
     - 说明
   * - ``param.raw``
     - ``Object | null``
     - raw 能力开启时的原始 libxposed 参数。
   * - ``param.getPackageName()``
     - ``String``
     - 当前目标包名。
   * - ``param.getApplicationInfo()``
     - ``ApplicationInfo``
     - 当前应用的 ``ApplicationInfo``。
   * - ``param.isFirstPackage()``
     - ``boolean``
     - 当前事件是否为该包首次加载。
   * - ``param.getDefaultClassLoader()``
     - ``ClassLoader``
     - 默认 ClassLoader。
   * - ``param.getClassLoader()``
     - ``ClassLoader``
     - 当前应用 ClassLoader；为空时回退到默认 ClassLoader。
   * - ``param.getAppComponentFactory()``
     - ``Object``
     - 当前 AppComponentFactory。
   * - ``param.getProcessName()``
     - ``String``
     - 当前进程名。
   * - ``param.isSystemServer()``
     - ``boolean``
     - 当前进程是否为 system_server。

示例：

.. code-block:: javascript

   xposed.onPackageLoaded(function (param) {
       if (param.getProcessName() !== env.packageName) {
           return;
       }
       console.log('main process loaded');
   });

xposed Hook 接口
------------------------

``xposed.hook`` 和 ``xposed.hookClassInitializer`` 是安装 Hook 的核心入口。

.. list-table::
   :header-rows: 1
   :widths: 35 25 40

   * - 接口
     - 返回值
     - 说明
   * - ``xposed.hook(executable)``
     - ``HookBuilder``
     - Hook 一个 ``Method`` 或 ``Constructor``。
   * - ``xposed.hookClassInitializer(clazz)``
     - ``HookBuilder``
     - Hook 指定类的类初始化器。
   * - ``xposed.deoptimize(executable)``
     - ``boolean``
     - 对方法或构造器做 deoptimize，常用于处理 ART 内联导致 Hook 不生效的场景。

参数：

``executable``
   Java ``java.lang.reflect.Method`` 或 ``java.lang.reflect.Constructor``。

``clazz``
   Java ``Class`` 对象。

基础示例：

.. code-block:: javascript

   xposed.onPackageLoaded(function () {
       const Application = Java.use('android.app.Application');
       const onCreate = Java.method(Application, 'onCreate');

       xposed.hook(onCreate).intercept(function (chain) {
           xposed.i('XHH', 'before Application.onCreate');
           const result = chain.proceed();
           xposed.i('XHH', 'after Application.onCreate');
           return result;
       });
   });

HookBuilder
~~~~~~~~~~~~~~~~~~

``xposed.hook`` 返回 ``HookBuilder``，用于配置 Hook 并安装拦截器。

.. list-table::
   :header-rows: 1
   :widths: 35 25 40

   * - 接口
     - 返回值
     - 说明
   * - ``builder.raw``
     - ``Object | null``
     - raw 能力开启时的原始 ``HookBuilder``。
   * - ``builder.setPriority(priority)``
     - ``HookBuilder``
     - 设置 Hook 优先级。
   * - ``builder.setExceptionMode(mode)``
     - ``HookBuilder``
     - 设置异常模式。
   * - ``builder.intercept(callback)``
     - ``HookHandle``
     - 安装拦截器。``callback`` 类型为 ``function(chain)``。

优先级常量：

.. code-block:: javascript

   xposed.PRIORITY_HIGHEST
   xposed.PRIORITY_DEFAULT
   xposed.PRIORITY_LOWEST

异常模式：

.. list-table::
   :header-rows: 1
   :widths: 35 65

   * - 常量
     - 说明
   * - ``xposed.ExceptionMode.DEFAULT``
     - 使用框架默认异常策略。
   * - ``xposed.ExceptionMode.PROTECTIVE``
     - 保护性模式，适合多数脚本。
   * - ``xposed.ExceptionMode.PASSTHROUGH``
     - 透传异常，适合调试或高级场景。

示例：

.. code-block:: javascript

   const handle = xposed.hook(targetMethod)
       .setPriority(xposed.PRIORITY_DEFAULT)
       .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
       .intercept(function (chain) {
           return chain.proceed();
       });

HookHandle
~~~~~~~~~~~~~~~~~~

``intercept`` 返回 ``HookHandle``。

.. list-table::
   :header-rows: 1
   :widths: 35 25 40

   * - 接口
     - 返回值
     - 说明
   * - ``handle.raw``
     - ``Object | null``
     - raw 能力开启时的原始 HookHandle。
   * - ``handle.getExecutable()``
     - ``Executable``
     - 返回被 Hook 的 Method 或 Constructor。
   * - ``handle.unhook()``
     - ``void``
     - 取消当前 Hook。

示例：

.. code-block:: javascript

   const handle = xposed.hook(method).intercept(function (chain) {
       handle.unhook();
       return chain.proceed();
   });

Chain
~~~~~~~~~~~~~~~~~~

``intercept`` 的回调参数 ``chain`` 表示一次实际调用。

.. list-table::
   :header-rows: 1
   :widths: 35 25 40

   * - 接口
     - 返回值
     - 说明
   * - ``chain.raw``
     - ``Object | null``
     - raw 能力开启时的原始 Chain。
   * - ``chain.getExecutable()``
     - ``Executable``
     - 当前被调用的方法或构造器。
   * - ``chain.getThisObject()``
     - ``Object | null``
     - 当前 ``this`` 对象；静态方法可能为空。
   * - ``chain.getArgs()``
     - ``List``
     - 当前参数列表。
   * - ``chain.getArg(index)``
     - ``Object``
     - 获取指定位置参数。
   * - ``chain.getArgsMutable()``
     - ``Object[]``
     - 获取参数数组副本，便于修改后传给 ``proceed(args)``。
   * - ``chain.proceed()``
     - ``Object``
     - 使用原始 this 与原始参数继续执行。
   * - ``chain.proceed(args)``
     - ``Object``
     - 使用新参数继续执行。
   * - ``chain.proceedWith(thisObject)``
     - ``Object``
     - 使用新的 this 和原始参数继续执行。
   * - ``chain.proceedWith(thisObject, args)``
     - ``Object``
     - 使用新的 this 与新参数继续执行。

读取参数：

.. code-block:: javascript

   xposed.hook(method).intercept(function (chain) {
       console.log('arg0=', chain.getArg(0));
       return chain.proceed();
   });

修改参数：

.. code-block:: javascript

   xposed.hook(method).intercept(function (chain) {
       const args = chain.getArgsMutable();
       args[0] = 'hooked';
       return chain.proceed(args);
   });

替换返回值：

.. code-block:: javascript

   xposed.hook(method).intercept(function () {
       return true;
   });

调用原方法后修改返回值：

.. code-block:: javascript

   xposed.hook(method).intercept(function (chain) {
       const result = chain.proceed();
       return '[XHH] ' + result;
   });

Hook 构造方法：

.. code-block:: javascript

   const File = Java.use('java.io.File');
   const ctor = Java.constructor(File, [Java.use('java.lang.String')]);

   xposed.hook(ctor).intercept(function (chain) {
       console.log('new File:', chain.getArg(0));
       return chain.proceed();
   });

Hook 类初始化器：

.. code-block:: javascript

   const Target = Java.use('com.example.Target');

   xposed.hookClassInitializer(Target).intercept(function (chain) {
       console.log('class init:', chain.getExecutable());
       return chain.proceed();
   });

Invoker 接口
------------------------

Invoker 用于调用方法、构造实例或执行特殊调用。

.. list-table::
   :header-rows: 1
   :widths: 35 25 40

   * - 接口
     - 返回值
     - 说明
   * - ``xposed.getInvoker(method)``
     - ``Invoker``
     - 为 ``Method`` 创建 Invoker。
   * - ``xposed.getInvoker(constructor)``
     - ``Invoker``
     - 为 ``Constructor`` 创建 Invoker。
   * - ``invoker.raw``
     - ``Object | null``
     - raw 能力开启时的原始 Invoker。
   * - ``invoker.setType(type)``
     - ``Invoker``
     - 设置底层调用类型，传入底层框架支持的 type 对象。
   * - ``invoker.invoke(thisObject)``
     - ``Object``
     - 调用方法，无参数。
   * - ``invoker.invoke(thisObject, args)``
     - ``Object``
     - 调用方法，``args`` 为数组或类数组。
   * - ``invoker.invokeSpecial(thisObject)``
     - ``Object``
     - special 调用，无参数。
   * - ``invoker.invokeSpecial(thisObject, args)``
     - ``Object``
     - special 调用，带参数。
   * - ``invoker.newInstance()``
     - ``Object``
     - 调用构造器创建实例。
   * - ``invoker.newInstance(args)``
     - ``Object``
     - 调用构造器创建实例，带参数。
   * - ``invoker.newInstanceSpecial(subClass)``
     - ``Object``
     - special 构造。
   * - ``invoker.newInstanceSpecial(subClass, args)``
     - ``Object``
     - special 构造，带参数。

调用方法示例：

.. code-block:: javascript

   const StringClass = Java.use('java.lang.String');
   const substring = Java.method(StringClass, 'substring', [Java.INT, Java.INT]);
   const invoker = xposed.getInvoker(substring);

   const result = invoker.invoke('hello world', [0, 5]);
   console.log(result); // hello

创建实例示例：

.. code-block:: javascript

   const StringBuilder = Java.use('java.lang.StringBuilder');
   const ctor = Java.constructor(StringBuilder);
   const builder = xposed.getInvoker(ctor).newInstance();
   console.log(builder.toString());

Java 反射桥接接口
------------------------

``Java`` 对象用于从 JS 侧取得 Java 类型和反射对象。

类型常量
~~~~~~~~~~~~~~~~~~

.. code-block:: javascript

   Java.BOOLEAN
   Java.BYTE
   Java.CHAR
   Java.SHORT
   Java.INT
   Java.LONG
   Java.FLOAT
   Java.DOUBLE
   Java.VOID

方法列表
~~~~~~~~~~~~~~~~~~

.. list-table::
   :header-rows: 1
   :widths: 35 25 40

   * - 接口
     - 返回值
     - 说明
   * - ``Java.type(className)``
     - ``Class``
     - 使用目标应用 ClassLoader 加载类。
   * - ``Java.use(className)``
     - ``Class``
     - ``Java.type`` 的别名。
   * - ``Java.classForName(className)``
     - ``Class``
     - 使用目标应用 ClassLoader 加载类。
   * - ``Java.classForName(className, initialize, loader)``
     - ``Class``
     - 指定是否初始化以及使用哪个 ClassLoader。
   * - ``Java.method(clazzOrName, methodName)``
     - ``Method``
     - 获取无参方法。
   * - ``Java.method(clazzOrName, methodName, parameterTypes)``
     - ``Method``
     - 按参数类型获取方法。
   * - ``Java.constructor(clazzOrName)``
     - ``Constructor``
     - 获取无参构造器。
   * - ``Java.constructor(clazzOrName, parameterTypes)``
     - ``Constructor``
     - 按参数类型获取构造器。
   * - ``Java.field(clazzOrName, fieldName)``
     - ``Field``
     - 获取字段并设为可访问。
   * - ``Java.newArray(componentType, length)``
     - Java 数组
     - 创建指定 Java 类型的数组。

参数说明：

``clazzOrName``
   可以传入 Java ``Class`` 对象，也可以传入完整类名字符串。

``parameterTypes``
   参数类型数组，例如 ``[Java.INT, Java.use('java.lang.String')]``。

加载类：

.. code-block:: javascript

   const Activity = Java.use('android.app.Activity');
   const Context = Java.type('android.content.Context');

获取重载方法：

.. code-block:: javascript

   const Toast = Java.use('android.widget.Toast');
   const Context = Java.use('android.content.Context');
   const CharSequence = Java.use('java.lang.CharSequence');

   const makeText = Java.method(Toast, 'makeText', [Context, CharSequence, Java.INT]);

获取字段：

.. code-block:: javascript

   const Build = Java.use('android.os.Build');
   const modelField = Java.field(Build, 'MODEL');
   console.log('model=', modelField.get(null));

创建数组：

.. code-block:: javascript

   const arr = Java.newArray(Java.INT, 3);
   arr[0] = 1;
   arr[1] = 2;
   arr[2] = 3;

日志接口
------------------------

``xposed`` 和 ``console`` 都可以输出日志。日志会写入 LSPosed 日志，同时通过 XiaoHeiHook 的日志广播进入软件内终端和 WebIDE 终端。

xposed.Log 常量
~~~~~~~~~~~~~~~~~~

.. code-block:: javascript

   xposed.Log.VERBOSE
   xposed.Log.DEBUG
   xposed.Log.INFO
   xposed.Log.WARN
   xposed.Log.ERROR
   xposed.Log.ASSERT

xposed 日志方法
~~~~~~~~~~~~~~~~~~

.. list-table::
   :header-rows: 1
   :widths: 35 25 40

   * - 接口
     - 返回值
     - 说明
   * - ``xposed.log(priority, tag, msg)``
     - ``void``
     - 按指定优先级写日志。
   * - ``xposed.log(priority, tag, msg, throwable)``
     - ``void``
     - 写日志并附带 Throwable。
   * - ``xposed.v(tag, msg)``
     - ``void``
     - verbose 日志。
   * - ``xposed.d(tag, msg)``
     - ``void``
     - debug 日志。
   * - ``xposed.i(tag, msg)``
     - ``void``
     - info 日志。
   * - ``xposed.w(tag, msg)``
     - ``void``
     - warn 日志。
   * - ``xposed.e(tag, msg)``
     - ``void``
     - error 日志。
   * - ``xposed.e(tag, msg, throwable)``
     - ``void``
     - error 日志并附带 Throwable。
   * - ``xposed.wtf(tag, msg)``
     - ``void``
     - assert 日志。

console 方法
~~~~~~~~~~~~~~~~~~

.. list-table::
   :header-rows: 1
   :widths: 35 25 40

   * - 接口
     - 返回值
     - 说明
   * - ``console.log(...messages)``
     - ``void``
     - debug 日志，多个参数以空格拼接。
   * - ``console.info(...messages)``
     - ``void``
     - info 日志。
   * - ``console.warn(...messages)``
     - ``void``
     - warn 日志。
   * - ``console.error(...messages)``
     - ``void``
     - error 日志。最后一个参数如果是 Throwable，会作为异常写入。

示例：

.. code-block:: javascript

   console.log('loaded', env.packageName, env.processName);
   xposed.i('XHH', 'hello');

   try {
       throw new Error('demo error');
   } catch (e) {
       console.error('failed', e);
   }

运行时信息接口
------------------------

这些接口透传底层框架和模块信息。

.. list-table::
   :header-rows: 1
   :widths: 40 25 35

   * - 接口
     - 返回值
     - 说明
   * - ``xposed.getApiVersion()``
     - ``Object``
     - 获取 libxposed API 版本。
   * - ``xposed.getFrameworkName()``
     - ``Object``
     - 获取框架名称。
   * - ``xposed.getFrameworkVersion()``
     - ``Object``
     - 获取框架版本。
   * - ``xposed.getFrameworkVersionCode()``
     - ``Object``
     - 获取框架版本号。
   * - ``xposed.getFrameworkProperties()``
     - ``Object``
     - 获取框架属性。
   * - ``xposed.getModuleApplicationInfo()``
     - ``Object``
     - 获取模块 ApplicationInfo。

示例：

.. code-block:: javascript

   console.log('framework=', xposed.getFrameworkName(), xposed.getFrameworkVersion());

堆栈接口
------------------------

堆栈接口用于排查调用来源。

.. list-table::
   :header-rows: 1
   :widths: 40 25 35

   * - 接口
     - 返回值
     - 说明
   * - ``xposed.getJavaStackTraceString()``
     - ``String``
     - 返回完整 Java 堆栈字符串。
   * - ``xposed.getAppStackTraceString()``
     - ``String``
     - 返回应用相关堆栈字符串，过滤部分框架内部调用。
   * - ``xposed.printJavaStackTrace(tag, title)``
     - ``void``
     - 输出完整 Java 堆栈到终端日志。
   * - ``xposed.printAppStackTrace(tag, title)``
     - ``void``
     - 输出应用相关 Java 堆栈到终端日志。
   * - ``xposed.printAppStack(tag, title)``
     - ``void``
     - ``printAppStackTrace`` 的别名。
   * - ``xposed.printJavaStack(tag, title)``
     - ``void``
     - ``printJavaStackTrace`` 的别名。
   * - ``xposed.getJavaStackTrace()``
     - ``List<Map>``
     - 返回完整结构化堆栈。
   * - ``xposed.getAppStackTrace()``
     - ``List<Map>``
     - 返回应用相关结构化堆栈。
   * - ``xposed.stackTrace()``
     - ``List<Map>``
     - 返回完整结构化堆栈。
   * - ``xposed.stackTrace(options)``
     - ``List<Map>``
     - 按选项返回结构化堆栈。

``stackTrace(options)`` 支持：

.. list-table::
   :header-rows: 1
   :widths: 25 25 50

   * - 选项
     - 类型
     - 说明
   * - ``appOnly``
     - ``boolean``
     - 是否只保留应用相关堆栈。
   * - ``maxDepth``
     - ``number``
     - 最大深度，最大会被限制为 512。
   * - ``skipNative``
     - ``boolean``
     - 是否跳过 native 方法。

示例：

.. code-block:: javascript

   xposed.hook(method).intercept(function (chain) {
       xposed.printAppStackTrace('XHH-Stack', 'target called');
       return chain.proceed();
   });

   const frames = xposed.stackTrace({
       appOnly: true,
       maxDepth: 40,
       skipNative: false
   });
   console.log(JSON.stringify(frames));

Remote Preferences 与 Remote Files
------------------------------------------

Remote Preferences 与 Remote Files 是 LSPosed 提供给模块和目标进程通信的机制。XiaoHeiHook 保留这些能力给脚本使用。

.. important::
   脚本自己的可视化设置推荐通过全局 ``settings`` 读取。不要直接写入 ``script_settings_<packageName>_<scriptId>`` 这类内部 key，否则会绕过 schema 校验。

.. list-table::
   :header-rows: 1
   :widths: 40 25 35

   * - 接口
     - 返回值
     - 说明
   * - ``xposed.getRemotePreferences(group)``
     - ``SharedPreferences``
     - 获取指定 group 的 LSPosed Remote Preferences。
   * - ``xposed.listRemoteFiles()``
     - ``String[]``
     - 列出 Remote Files 名称。
   * - ``xposed.openRemoteFile(name)``
     - ``ParcelFileDescriptor``
     - 打开 Remote File。
   * - ``xposed.readRemoteText(name)``
     - ``String``
     - 读取 Remote File 的 UTF-8 文本内容。

读取 Remote Preferences：

.. code-block:: javascript

   const prefs = xposed.getRemotePreferences('XiaoHeiHookSetting');
   const enabled = prefs.getBoolean('some_key', false);
   console.log('enabled=', enabled);

读取 Remote File：

.. code-block:: javascript

   const names = xposed.listRemoteFiles();
   console.log('remote files=', names.join(','));

   const text = xposed.readRemoteText('some_file.txt');
   console.log(text);

settings 脚本设置接口
--------------------------

``settings`` 是脚本设置的只读便利层。运行时会先读取 ``settings.json`` 的默认值，再叠加当前应用保存的用户值，最终把合并结果暴露给脚本。

.. list-table::
   :header-rows: 1
   :widths: 35 25 40

   * - 接口
     - 返回值
     - 说明
   * - ``settings.raw``
     - ``String``
     - 合并后的配置 JSON 字符串。
   * - ``settings.get(key)``
     - ``Object | null``
     - 读取配置值，不存在时返回 ``null``。
   * - ``settings.get(key, defaultValue)``
     - ``Object``
     - 读取配置值，不存在时返回默认值。
   * - ``settings.has(key)``
     - ``boolean``
     - 判断配置是否包含某个 key。
   * - ``settings.all()``
     - ``Map``
     - 返回所有合并后的配置值副本。

``env.settings`` 与全局 ``settings`` 指向同一个配置对象。

示例：

.. code-block:: javascript

   const enabled = settings.get('enabled', true);
   const tag = settings.get('tag', 'XHH');
   const rules = settings.get('rules', []);

   if (!enabled) {
       console.log('script disabled by settings');
       return;
   }

   xposed.i(tag, 'rules=' + JSON.stringify(rules));

env 环境对象
------------------

``env`` 保存当前脚本与目标进程信息。

.. list-table::
   :header-rows: 1
   :widths: 35 25 40

   * - 属性
     - 类型
     - 说明
   * - ``env.scriptName``
     - ``String``
     - 当前脚本显示名。
   * - ``env.scriptPath``
     - ``String``
     - 当前脚本相对路径。
   * - ``env.sourceName``
     - ``String``
     - Rhino sourceName，通常等于脚本相对路径。
   * - ``env.packageName``
     - ``String``
     - 当前目标包名。
   * - ``env.processName``
     - ``String``
     - 当前目标进程名。
   * - ``env.classLoader``
     - ``ClassLoader``
     - 当前目标应用 ClassLoader。
   * - ``env.raw``
     - ``Object | null``
     - raw 能力开启时的原始当前事件参数。
   * - ``env.settings``
     - ``SettingsFacade``
     - 等同于全局 ``settings``。

示例：

.. code-block:: javascript

   console.log('script=', env.scriptPath);
   console.log('target=', env.packageName, env.processName);

require 多文件脚本接口
--------------------------

``require(path)`` 用于加载同一个多文件脚本目录内的其他 JS 文件。它采用 CommonJS 风格包装模块。

接口：

.. code-block:: javascript

   const moduleValue = require(path);

参数：

``path``
   只能是相对路径，必须以 ``./`` 或 ``../`` 开头。支持省略 ``.js``，也支持加载目录下的 ``index.js``。

返回值：

返回目标模块的 ``module.exports``。如果模块没有修改 ``module.exports``，则返回 ``exports`` 对象。

路径解析规则：

.. list-table::
   :header-rows: 1
   :widths: 35 65

   * - 写法
     - 解析候选
   * - ``require('./logger')``
     - ``logger``、``logger.js``、``logger/index.js``。
   * - ``require('./logger.js')``
     - ``logger.js``。
   * - ``require('../shared/config')``
     - 相对当前文件所在目录解析，禁止越界到脚本根目录之外。

示例目录：

.. code-block:: text

   okhttp_logger/
   ├─ index.js
   ├─ logger.js
   └─ config.js

``logger.js``：

.. code-block:: javascript

   exports.info = function (message) {
       xposed.i('XHH-Logger', message);
   };

``index.js``：

.. code-block:: javascript

   const logger = require('./logger');

   xposed.onPackageLoaded(function () {
       logger.info('script loaded');
   });

.. note::
   ``require`` 加载的模块会缓存。同一个路径多次 ``require`` 会返回同一个导出对象。

xposed.raw 接口
------------------------

``xposed.raw`` 提供底层对象访问能力。

属性：

.. list-table::
   :header-rows: 1
   :widths: 40 25 35

   * - 属性
     - 类型
     - 说明
   * - ``xposed.raw.enabled``
     - ``boolean``
     - raw 能力是否开启。
   * - ``xposed.raw.interfaceObject``
     - ``Object | null``
     - 原始 XposedInterface / 模块接口对象。
   * - ``xposed.raw.module``
     - ``Object | null``
     - 原始模块对象。
   * - ``xposed.raw.currentParam``
     - ``Object | null``
     - 当前生命周期原始参数。
   * - ``xposed.raw.currentPackageLoadedParam``
     - ``Object | null``
     - package-loaded 原始参数。
   * - ``xposed.raw.currentPackageReadyParam``
     - ``Object | null``
     - package-ready 原始参数。
   * - ``xposed.raw.currentModuleLoadedParam``
     - ``Object | null``
     - module-loaded 原始参数。
   * - ``xposed.raw.currentSystemServerStartingParam``
     - ``Object | null``
     - system-server-starting 原始参数。
   * - ``xposed.raw.classLoader``
     - ``ClassLoader``
     - 当前目标应用 ClassLoader。
   * - ``xposed.raw.packageName``
     - ``String``
     - 当前包名。
   * - ``xposed.raw.processName``
     - ``String``
     - 当前进程名。

方法：

.. list-table::
   :header-rows: 1
   :widths: 40 25 35

   * - 接口
     - 返回值
     - 说明
   * - ``xposed.raw.enabled()``
     - ``boolean``
     - 返回 raw 能力是否开启。
   * - ``xposed.raw.getInterface()``
     - ``Object | null``
     - 获取原始接口对象。
   * - ``xposed.raw.unwrap(value)``
     - ``Object``
     - 解包 Rhino Wrapper。
   * - ``xposed.raw.typeOf(value)``
     - ``String``
     - 返回解包后的 Java 类型名。
   * - ``xposed.raw.call(target, methodName)``
     - ``Object``
     - 调用目标对象方法，无参数。
   * - ``xposed.raw.call(target, methodName, args)``
     - ``Object``
     - 调用目标对象方法，带参数。
   * - ``xposed.raw.field(targetOrClass, fieldName)``
     - ``Field``
     - 获取字段。
   * - ``xposed.raw.method(clazzOrName, methodName)``
     - ``Method``
     - 获取方法。
   * - ``xposed.raw.method(clazzOrName, methodName, parameterTypes)``
     - ``Method``
     - 按参数类型获取方法。
   * - ``xposed.raw.constructor(clazzOrName)``
     - ``Constructor``
     - 获取构造器。
   * - ``xposed.raw.constructor(clazzOrName, parameterTypes)``
     - ``Constructor``
     - 按参数类型获取构造器。

示例：

.. code-block:: javascript

   if (xposed.raw.enabled()) {
       console.log('raw type=', xposed.raw.typeOf(xposed.raw.getInterface()));
   }

.. warning::
   raw 接口绕过了 XiaoHeiHook 的封装层。除非确实需要访问未封装的底层对象，否则不建议使用。

调试接口 debuggerx
------------------------

``debuggerx`` 用于 WebIDE 软断点、行断点配合和观察值输出。

.. list-table::
   :header-rows: 1
   :widths: 35 25 40

   * - 接口
     - 返回值
     - 说明
   * - ``debuggerx.isEnabled()``
     - ``boolean``
     - 当前是否启用软调试器。
   * - ``debuggerx.pause(name)``
     - ``Object``
     - 触发软断点，等同于 ``breakpoint(name)``。
   * - ``debuggerx.breakpoint(name)``
     - ``Object``
     - 触发软断点。
   * - ``debuggerx.breakpoint(name, locals)``
     - ``Object``
     - 触发软断点，并上传局部变量对象。
   * - ``debuggerx.hasLineBreakpoints()``
     - ``boolean``
     - 当前脚本是否存在行断点。
   * - ``debuggerx.log(name, value)``
     - ``void``
     - 向调试器发送观察值。

示例：

.. code-block:: javascript

   const userId = '10001';

   debuggerx.breakpoint('before-request', {
       userId: userId,
       packageName: env.packageName,
       processName: env.processName
   });

   debuggerx.log('userId', userId);

完整示例：Hook Toast.makeText
----------------------------------

.. code-block:: javascript

   // ==LSPosedScript==
   // @name         Toast 文本日志
   // @id           demo.toast.text.log
   // @target       *
   // @process      *
   // @run-at       package-loaded
   // @grant        java.full
   // @grant        xposed.full
   // ==/LSPosedScript==

   const TAG = 'XHH-Toast';

   xposed.onPackageLoaded(function () {
       const Toast = Java.use('android.widget.Toast');
       const Context = Java.use('android.content.Context');
       const CharSequence = Java.use('java.lang.CharSequence');

       const makeText = Java.method(Toast, 'makeText', [
           Context,
           CharSequence,
           Java.INT
       ]);

       xposed.hook(makeText)
           .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
           .intercept(function (chain) {
               const text = chain.getArg(1);
               xposed.i(TAG, 'Toast text=' + text);
               return chain.proceed();
           });
   });

完整示例：根据设置项控制 Hook
----------------------------------

.. code-block:: javascript

   // ==LSPosedScript==
   // @name         OkHttp 日志
   // @id           demo.okhttp.logger
   // @target       *
   // @process      *
   // @run-at       package-loaded
   // @grant        java.full
   // @grant        xposed.full
   // ==/LSPosedScript==

   const enabled = settings.get('enabled', true);
   const tag = settings.get('tag', 'XHH-OkHttp');
   const logHeaders = settings.get('logHeaders', true);

   if (!enabled) {
       console.log('OkHttp logger disabled');
       return;
   }

   xposed.onPackageLoaded(function () {
       xposed.i(tag, 'logHeaders=' + logHeaders);
       // 这里继续查找目标类并安装 Hook。
   });
