动态 Dex 扫描
================================================================================

动态 Dex 扫描用于解决目标应用存在混淆、拆包、动态加载、内存 Dex 或加固释放时，
脚本无法稳定写死类名和方法名的问题。脚本可以先通过 dex 字节码特征定位目标方法，
再把结果转换为 ``java.lang.reflect.Method`` 交给 ``xposed.hook()`` 使用。

典型场景：

* 类名、方法名被混淆或版本间频繁变化。
* APK 存在 ``classes2.dex``、split APK、插件 dex 或动态加载 dex。
* 壳或热修复框架只在运行后释放业务 dex。
* 目标方法可以通过字符串、invoke、proto、smali 片段等特征定位。
* 需要把 ClassLoader、文件、运行时注册表、cookie dump、memory dump 统一纳入分析。

.. warning::

   动态扫描能力只应用于你有权调试、分析和修改的应用或测试环境。面对加固壳时，
   Java 层扫描不一定能取得真实业务 dex，需要运行时捕获、cookie dump 或内存扫描辅助。
   
.. tip::
	Dex / DumpDex 内部调试日志默认降噪。需要排查 dump、cookie、runtime capture 或内存扫描
	流程时，在脚本头加入：

	.. code-block:: javascript

	   // @grant dex.debug

	``dex.debug`` 只开启日志，不单独授予 dump 权限。

.. toctree::
   :maxdepth: 1

   stage_selection
   source_api
   runtime_capture
   memory_scan
   method_search
   examples
   troubleshooting
   limits
