排错
===========

脚本里 dex 未定义
--------------------

确认脚本头部有：

.. code-block:: javascript

   // @grant        dex.dump

没有对应 grant 时，默认不注入 ``dex``。

找不到 pc.a
-----------

确认是在目标应用自己的 ``Application.attach(Context)`` 之后取 ``context.getClassLoader()``，不要使用模块默认 ClassLoader。

Dump 成功但 featuresOk=false
----------------------------

说明目标 dex 被导出，但目标方法体没有命中预期字符串/调用。请查看 ``inspectMethodInFile`` 输出的：

* ``strings``
* ``invokes``
* ``missingStrings``
* ``missingInvokeContains``
* ``smaliHead``

OOM
---

不要一次打开全部 cookie dex 搜索。推荐 ``dumpAndInspectMethod``，它只定位目标方法所在 dex 并 inspect 单个文件。

