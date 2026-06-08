运行时 Dex 捕获
===============

运行时 Dex 捕获用于记录应用启动后新增的 dex 来源。它解决的是 ``fromLoader`` 只能看到初始 dex path 的问题。

入口
----

``dex.watchLoaders()``
----------------------

安装运行时捕获 hook。

示例：

.. code-block:: javascript

   try {
     dex.watchLoaders();
     console.log("runtime dex watcher installed");
   } catch (e) {
     console.log("watchLoaders failed: " + e);
   }

建议在脚本加载后尽早调用。如果 Java 层已经在 ``HookEntry`` 中提前安装捕获，则该调用可以作为兜底。

``dex.runtimeSources()``
------------------------

返回捕获到的 dex 来源。

示例：

.. code-block:: javascript

   var sources = dex.runtimeSources();
   console.log(JSON.stringify(sources));

可能的来源类型：

* ``DexClassLoader.<init>``
* ``PathClassLoader.<init>``
* ``BaseDexClassLoader.addDexPath``
* ``DexFile.loadDex``
* ``DexPathList.makeDexElements``
* ``InMemoryDexClassLoader.dump``

``dex.runtimeLoaders()``
------------------------

返回捕获到的 ClassLoader 信息。

示例：

.. code-block:: javascript

   var loaders = dex.runtimeLoaders();
   console.log(JSON.stringify(loaders));

这对 ``toMethod`` 很重要，因为动态 dex 中的类可能并不属于初始应用 ``ClassLoader``。

``dex.registerLoader(loader, path)``
------------------------------------

手动注册一个 loader 和 dex 路径。

示例：

.. code-block:: javascript

   dex.registerLoader(loader, "/data/user/0/pkg/files/plugin.dex");

适用场景：

* 脚本自己知道 dex 路径。
* 自动捕获漏掉了某个路径。
* 需要临时把路径加入 runtime registry。

捕获点说明
----------

建议 Java 层尽早安装以下 hook：

* ``dalvik.system.DexClassLoader`` 构造函数。
* ``dalvik.system.PathClassLoader`` 构造函数。
* ``dalvik.system.DelegateLastClassLoader`` 构造函数。
* ``dalvik.system.InMemoryDexClassLoader`` 构造函数。
* ``dalvik.system.DexFile.loadDex``。
* ``dalvik.system.DexFile.openDexFile``。
* ``dalvik.system.DexPathList.makeDexElements``。
* ``dalvik.system.DexPathList.makePathElements``。

如果目标壳完全通过 native/ART 内部加载业务 dex，Java 层捕获可能只能看到壳类，例如：

.. code-block:: text

   com.stub.StubApp.*
   com.tianyu.util.*

这种情况应进入内存扫描阶段。

生命周期触发扫描
----------------

在 Rhino/Android 环境中，不建议在 JS 中使用 ``JavaAdapter`` 创建 ``Runnable``。可以通过生命周期多次触发扫描：

.. code-block:: javascript

   Application.attach
   Application.onCreate
   Activity.onCreate
   Activity.onStart
   Activity.onResume

示例：

.. code-block:: javascript

   var lastScanTime = 0;
   var SCAN_INTERVAL_MS = 1500;

   function tryScan(reason) {
     var now = new Date().getTime();
     if (now - lastScanTime < SCAN_INTERVAL_MS) return;
     lastScanTime = now;

     console.log("scan reason=" + reason);
     console.log(JSON.stringify(dex.runtimeSources()));
   }
