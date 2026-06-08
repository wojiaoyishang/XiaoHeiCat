动态 Dex 扫描
===========================

本文档介绍 XiaoHeiHook 新加入的动态 Dex 扫描能力。

动态 Dex 扫描用于解决脚本在面对混淆、拆包、动态加载、内存 Dex 或加固壳时，无法稳定写死类名和方法名的问题。脚本可以通过 Dex 字节码特征定位目标方法，再将扫描结果转换为 ``java.lang.reflect.Method``，交给 ``xposed.hook()`` 使用。

典型场景包括：

* 目标类名、方法名被混淆。
* APK 存在 ``classes2.dex``、split APK 或动态加载 dex。
* 壳只在启动后释放业务 dex。
* 目标方法可以通过字符串、调用目标、返回值、参数、static 等特征定位。
* 需要从 ``ClassLoader``、文件、运行时注册表或内存 dump 中统一分析 Dex。

.. warning::

   动态扫描能力用于你有权调试、分析和修改的应用或测试环境。面对加固壳时，Java 层扫描不一定能取得真实业务 dex；此时需要运行时捕获或内存扫描辅助。

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
