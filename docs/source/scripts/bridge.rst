从 LSPosed 到 JS 脚本
====================================

XiaoHeiHook 的 JS API 是对现代 LSPosed/libxposed API 的一层脚本化封装。理解这层映射关系，有助于判断某个 JS 方法背后实际调用的是哪个 libxposed 能力。

.. tip::

	虽然代码是通过 JS 脚本进行编写的，但是对于处理一些来自 Java 层的对象（如 Java 的 String），不建议直接使用：
	 
	.. code-block:: javascript
	
		"com.example.app" !== param.getPackageName()  // 这样比较始终返回 false，因为类型对不上
		
	的方式进行比较，而是使用：
		
	.. code-block:: javascript
	
		param.getPackageName().equals("com.example.app")  // 使用 Java 的比较方法
		// 或者
		"com.example.app" !== String(param.getPackageName())  // 先进行一次数据类型转化
		
	在使用 Java 对象和 JS 对象进行脚本编写的时候需要注意。
	
	更多语法边界和说明参考 :ref:`脚本语法边界` 章节。

整体调用链
------------------

XiaoHeiHook 的执行脚本链路可以理解为：

.. image:: ../_static/整体调用链.drawio.png
   :target: ../_static/整体调用链.drawio.png
   :align: center


整体调用链可以理解为从 LSPosed 框架层进入 XiaoHeiHook 应用层的执行流程。

首先，LSPosed 在目标进程中加载模块，进入继承自 ``XposedModule`` 的 ``HookEntry``。随后框架会按时机触发生命周期方法，例如 ``onModuleLoaded``、``onPackageLoaded``、``onPackageReady`` 等。

进入 XiaoHeiHook  的 Hook 入口之后，模块不会直接执行全部脚本，而是先根据配置进行筛选，包括应用开关、脚本开关、``@target``、``@process``、``@run-at`` 等条件。只有匹配当前应用和进程的脚本才会继续执行。

筛选完成后，XiaoHeiHook 会从 Remote Files 中读取已经同步到 LSPosed 的脚本源码，并交给 ``JsHookRuntime``。运行时负责创建 Rhino JavaScript 环境，注入 ``xposed``、``Java``、``env``、``settings`` 等全局对象。

最后，用户脚本在 JS 环境中运行，通过 ``xposed.hook`` 等接口间接调用现代 LSPosed/libxposed 的 Hook 能力，从而完成方法拦截、日志输出、配置读取等操作。

libxposed 到 JS 的映射
--------------------------------------

.. note::
   不鼓励使用反射方式直接使用 Xposed 框架的能力，请遵循保留的 API 接口，否则请尝试关闭 Xposed API 调用保护。

.. list-table::
   :header-rows: 1
   :widths: 35 35 30

   * - libxposed / Java 能力
     - JS 暴露接口
     - 说明
   * - ``XposedModule.onModuleLoaded``
     - ``xposed.onModuleLoaded(callback)``
     - 模块加载事件。
   * - ``XposedModule.onPackageLoaded``
     - ``xposed.onPackageLoaded(callback)``
     - 包加载事件，通常用于安装大多数 Hook。
   * - ``XposedModule.onPackageReady``
     - ``xposed.onPackageReady(callback)``
     - 应用更完整初始化后的事件。
   * - ``XposedModule.onSystemServerStarting``
     - ``xposed.onSystemServerStarting(callback)``
     - system_server 启动事件。
   * - ``XposedInterface.hook(Executable)``
     - ``xposed.hook(executable)``
     - Hook Method 或 Constructor。
   * - ``XposedInterface.hookClassInitializer(Class)``
     - ``xposed.hookClassInitializer(clazz)``
     - Hook 类初始化器。
   * - ``HookBuilder.setPriority``
     - ``builder.setPriority(priority)``
     - 设置 Hook 优先级。
   * - ``HookBuilder.setExceptionMode``
     - ``builder.setExceptionMode(mode)``
     - 设置 Hook 异常模式。
   * - ``HookBuilder.intercept``
     - ``builder.intercept(callback)``
     - 安装拦截器，回调收到 ``chain``。
   * - ``XposedInterface.Chain``
     - ``chain``
     - 表示一次被 Hook 方法调用。
   * - ``Chain.proceed``
     - ``chain.proceed()`` / ``chain.proceed(args)``
     - 继续执行原方法。
   * - ``HookHandle.unhook``
     - ``handle.unhook()``
     - 取消 Hook。
   * - ``XposedInterface.getInvoker``
     - ``xposed.getInvoker(methodOrConstructor)``
     - 创建 Invoker。
   * - ``XposedInterface.deoptimize``
     - ``xposed.deoptimize(executable)``
     - 对方法或构造器去优化。
   * - ``XposedInterface.log``
     - ``xposed.log`` / ``xposed.i`` / ``console.log``
     - 输出到 LSPosed 与 XiaoHeiHook 终端。
   * - ``getRemotePreferences``
     - ``xposed.getRemotePreferences(group)``
     - 读取 LSPosed Remote Preferences。
   * - ``listRemoteFiles`` / ``openRemoteFile``
     - ``xposed.listRemoteFiles`` / ``xposed.openRemoteFile``
     - 访问同步后的 Remote Files。
   * - Java 反射
     - ``Java.use`` / ``Java.method`` / ``Java.constructor``
     - 获取 Class、Method、Constructor、Field。

Hook 模型差异
------------------

现代 libxposed 的 Hook 不是旧式 ``beforeHookedMethod`` / ``afterHookedMethod`` / ``replaceHookedMethod`` 三段式，而是更接近拦截器链模型：

.. code-block:: javascript

   xposed.hook(method).intercept(function (chain) {
       // before
       const result = chain.proceed();
       // after
       return result;
   });

是否执行原方法、使用哪些参数执行、最终返回什么值，都由回调自己决定：

.. code-block:: javascript

   // 继续原方法
   return chain.proceed();

   // 修改参数后继续原方法
   const args = chain.getArgsMutable();
   args[0] = 'new value';
   return chain.proceed(args);

   // 直接替换返回值，不执行原方法
   return true;

.. important::
   XiaoHeiHook 文档中的 ``chain`` 对象对应现代 libxposed 的 ``XposedInterface.Chain``。脚本不应该再按旧 Xposed 的 ``param.args`` / ``param.result`` 思路编写。

获取 Method 或 Constructor
----------------------------------------

``xposed.hook`` 需要的参数是 Java ``Method`` 或 ``Constructor``。JS 侧通过 ``Java`` 桥接对象取得它们：

.. code-block:: javascript

   const Activity = Java.use('android.app.Activity');
   const onResume = Java.method(Activity, 'onResume');

   xposed.hook(onResume).intercept(function (chain) {
       xposed.i('XHH', 'Activity.onResume');
       return chain.proceed();
   });

也可以直接调用 Java 反射方法：

.. code-block:: javascript

   const Activity = Java.use('android.app.Activity');
   const onResume = Activity.getDeclaredMethod('onResume');
   onResume.setAccessible(true);

   xposed.hook(onResume).intercept(function (chain) {
       return chain.proceed();
   });

raw 对象的定位
------------------

多数脚本只需要使用 XiaoHeiHook 封装后的 JS API。只有需要访问底层 libxposed 对象时，才使用 ``xposed.raw``、``chain.raw``、``handle.raw`` 或 ``invoker.raw``。

.. warning::
   raw 对象直接暴露 Java 底层对象，兼容性与安全性由脚本作者自行负责。普通脚本应优先使用文档中列出的稳定封装接口。
