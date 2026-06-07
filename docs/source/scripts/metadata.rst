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
   // @url          https://example.com/okhttp_logger.js
   // @url-refresh  false
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
   * - ``@url``
     - 空
     - URL 脚本来源。
   * - ``@url-refresh``
     - ``false``
     - 同步时是否刷新 URL 脚本。也兼容 ``@url-refresh-on-apply``、``@refresh-url``、``@remote-refresh``。

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

