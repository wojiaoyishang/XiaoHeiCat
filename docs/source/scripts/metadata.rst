脚本元数据
==================

脚本顶部可以使用 ``LSPosedScript`` 块声明脚本信息。

完整示例
-----------------

.. code-block:: javascript

   // ==LSPosedScript==
   // @name         OkHttp 日志脚本
   // @id           demo.okhttp.logger
   // @version      1.0.0
   // @author       XiaoHeiHook
   // @description  打印 OkHttp 请求日志
   // @target       com.example.app
   // @process      *
   // @run-at       package-loaded
   // @grant        java.full
   // @grant        xposed.full
   // @grant        xposed.raw
   // ==/LSPosedScript==

字段说明
-----------------

.. list-table::
   :header-rows: 1
   :widths: 20 25 55

   * - 字段
     - 默认值
     - 说明
   * - ``@name``
     - ``@id``
     - 脚本显示名称。
   * - ``@id``
     - 文件名
     - 脚本唯一 ID。应用开关、脚本开关、设置项保存都会使用它。
   * - ``@version``
     - ``1.0.0``
     - 脚本版本。
   * - ``@author``
     - ``Unknown``
     - 作者名称。
   * - ``@description``
     - 空
     - 脚本描述。
   * - ``@target``
     - 空
     - 目标包名。可多次声明，也可以用逗号或空格分隔。为空或 ``*`` 表示匹配全部应用。
   * - ``@process``
     - 空
     - 目标进程名。为空或 ``*`` 表示匹配全部进程。
   * - ``@run-at``
     - ``package-loaded``
     - 脚本运行事件。
   * - ``@grant``
     - 空
     - 声明脚本需要的能力，用于标记和管理。


.. note::
   自 1.1 版本起，不再支持 URL 远程脚本同步。对于 grant 字段说明请查看 :ref:`权限边界` 章节。

.. note::
   从 ``1.30 (106)`` 起，新增 MCP 远程调用相关 grant、内部调试日志 grant，以及 ``xhh`` 全局对象。旧脚本不声明这些 grant 时不会自动开启远程注册或内部调试日志。

常用 grant
----------------------------------------------------------

``@grant`` 用于声明脚本需要的能力。XiaoHeiHook 会根据 grant 决定是否注入对应对象、是否开启额外能力、是否输出内部调试日志。

.. list-table:: 常用 grant
   :header-rows: 1
   :widths: 30 70

   * - grant
     - 说明
   * - ``java.full``
     - 允许使用 Java 类访问和反射辅助能力。
   * - ``xposed.full``
     - 允许使用 Xposed / LSPosed Hook 能力。
   * - ``xposed.raw``
     - 允许访问底层 raw 对象。仅建议高级脚本使用。
   * - ``rpc.register``
     - 声明脚本会注册 MCP 远程可调用方法。通常与 ``xhh.rpc.register_method`` 配合使用。
   * - ``mcp.debug``
     - 输出 MCP bridge 内部调试日志。只影响日志，不授予远程调用能力。
   * - ``xhh.debug``
     - 输出 XiaoHeiHook 内部全局调试日志，包含 MCP 与 Dex 调试日志。
   * - ``dex.dump``
     - 开启 DumpDex / dex dump 能力。
   * - ``dex.read``
     - 开启 Dex 读取能力。
   * - ``dex.search``
     - 开启 Dex 搜索能力。
   * - ``dex.full``
     - 开启完整 Dex 能力。
   * - ``dex.debug``
     - 输出 Dex / DumpDex 内部调试日志。只影响日志，不授予 dump 权限。

.. tip::
   ``mcp.debug``、``dex.debug``、``xhh.debug`` 都是调试日志开关。它们不会替代 ``rpc.register`` 或 ``dex.dump`` 这类能力 grant。

MCP 脚本头示例
----------------------------------------------------------

.. code-block:: javascript

   // ==UserScript==
   // @name         MCP Echo Test
   // @target       cn.am7code.tools
   // @process      cn.am7code.tools
   // @run-at       package-loaded
   // @grant        rpc.register
   // ==/UserScript==

需要排查 MCP 注册过程时，可以临时加入：

.. code-block:: javascript

   // @grant        mcp.debug

DumpDex 调试脚本头示例
----------------------------------------------------------

.. code-block:: javascript

   // ==UserScript==
   // @name         DumpDex Debug
   // @target       cn.example.app
   // @grant        dex.dump
   // @grant        dex.debug
   // ==/UserScript==

.. warning::
   ``dex.debug`` 只会增加内部日志。真正允许 dump dex 的仍然是 ``dex.dump`` 或 ``dex.full``。

run-at
-----------------

常用值：

.. list-table::
   :header-rows: 1
   :widths: 30 70

   * - 值
     - 说明
   * - ``package-loaded``
     - 目标包加载时运行。大多数应用 Hook 使用这个事件。
   * - ``package-ready``
     - 目标包 ready 后运行，适合需要更晚阶段 ClassLoader 的场景。
   * - ``module-loaded``
     - 模块加载事件。运行时接口已暴露，具体取决于模块分发入口。
   * - ``system-server-starting``
     - System Server 启动事件。运行时接口已暴露，适合系统进程相关扩展。

.. note::
   当前应用脚本最常用、最稳定的事件是 ``package-loaded`` 与 ``package-ready``。

目标匹配
-----------------

匹配单个应用：

.. code-block:: javascript

   // @target       com.example.app

匹配多个应用：

.. code-block:: javascript

   // @target       com.example.app, com.demo.app

匹配所有应用：

.. code-block:: javascript

   // @target       *

匹配指定进程：

.. code-block:: javascript

   // @process      com.example.app:remote



1.30 (106) 返回值稳定化与接口收紧
--------------------------------------

复杂 JS API 返回值会优先提供稳定对象字段，例如 ``paths``、``results``、``methods``。``sources``、``details`` 等字段仅建议用于日志与排障。

这是一次版本跨越：新脚本应使用 JS 对象和数组，不再为 Java ``List<Map>`` 的旧访问链编写兜底逻辑。
Dex 旧别名与目录扫描过渡入口已收紧或移除，详见 :doc:`dynamic_dex_scan/source_api`。需要判断一个值是 JS 对象还是 Java bridge 对象时，使用 ``xhh.objectKind(value)``。


1.31 (108) 作用域修复与全局状态
--------------------------------------

``1.31 (108)`` 修复 JS Runtime 中 Hook 回调、Java SAM 回调和 RPC 回调的作用域复用问题。
同一脚本内的顶层 ``let``、``var`` 和普通对象字段可以在这些回调之间保持状态。

同时新增 ``xhh.global``，用于在同一目标 App 进程内跨脚本、跨回调保存运行期对象和值。
它适合保存 Java ``Method``、``thisObject``、``ClassLoader`` 等不能简单序列化的对象。

.. warning::

   ``xhh.global`` 不会跨进程持久化。目标 App 被终止或重启后，需要重新通过 Hook 捕获并写入。


1.32 (109) Java.to 与反射签名快捷写法
--------------------------------------

``1.32 (109)`` 新增 ``Java.to(type, value[, options])``，用于在 JS 侧显式构造 Java
基础类型、包装类型、数组、集合、``BigInteger``、``BigDecimal`` 等值。

本版本还明确并加固了 JS 与 Java 之间的参数传递规则：Java / LSPosed 传入 JS 的对象保持现有脚本 API 行为；
脚本主动调用 Java 方法时，只有普通 JS 值会按目标 Java 签名自动转换，已经是 Java 对象或 Java wrapper 的值会直接传递。
这避免了显式构造的 ``Integer``、``Long``、``Method``、``Class`` 等对象被误转换为 JS 基础类型。

同时，``getDeclaredMethod``、``getMethod``、``getDeclaredConstructor``、``getConstructor``
等包装后的 Java 反射方法支持签名字符串快捷写法，可以直接传 ``"java.lang.String"``、
``"int"``、``"long"`` 等类名或基础类型名称。
