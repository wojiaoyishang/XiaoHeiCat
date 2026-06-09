脚本结构
==================

单文件脚本
-----------------

单文件脚本直接放在 ``Documents/XiaoHeiHook`` 根目录：

.. code-block:: text

   Documents/XiaoHeiHook/demo.js

脚本文件必须以 ``.js`` 结尾，并建议在文件顶部声明 ``LSPosedScript`` 元数据。

多文件脚本
-----------------

多文件脚本必须是目录，并且目录下必须存在 ``index.js``：

.. code-block:: text

   Documents/XiaoHeiHook/okhttp_logger/
   ├─ index.js
   ├─ logger.js
   ├─ okhttp.js
   ├─ settings.json
   └─ assets/
      └─ icon.png

扫描规则：

.. code-block:: text

   目录/index.js 存在
      => 有效多文件脚本

   目录/settings.json 存在
      => 该脚本有可视化设置项

.. important::
   有设置项的脚本必须使用多文件结构。单文件 ``.js`` 不支持 ``settings.json``。

CommonJS require
-----------------

多文件脚本可以使用 ``require`` 加载同目录或子目录中的 JS 文件。

.. code-block:: javascript

   // index.js
   const logger = require('./logger.js');
   logger.info('hello');

.. code-block:: javascript

   // logger.js
   exports.info = function (message) {
       xposed.i('XHH', message);
   };

``require`` 会缓存模块结果，同一个文件多次 require 不会重复执行。

assets 资源目录
-----------------

多文件脚本可以包含 ``assets/`` 目录，用于存放 HTML、CSS、图片、JSON、本地图标等
非 JS 资源。脚本通过 ``xhh.fs.readAssetText``、``xhh.fs.readAssetBytes``、
``xhh.fs.copyAssetToApp`` 和 ``xhh.fs.syncAssetsToApp`` 访问这些资源。

.. important::
   ``assets/`` 只用于资源文件，不会参与 ``require`` 模块加载。需要被 ``require``
   的 JS 文件应该放在脚本目录或 ``lib/`` 等普通目录中，不要放进 ``assets/``。

.. tip::
   资源要交给目标 App 的 ``ImageView``、``WebView`` 或 Java 业务代码使用时，推荐先复制到
   目标 App 私有目录，再使用返回值里的完整路径。

同步规则
-----------------

多文件脚本同步时会同步：

- ``index.js``
- ``settings.json``
- 其他依赖 JS 文件
- ``assets/`` 下的资源文件

同步到目标进程后，脚本通过 Remote Files 读取内容。同步文件会带有 SHA-256 校验，避免目标进程读取到不一致的脚本内容。

.. note::
   ``assets/`` 会被同步给 ``xhh.fs`` 读取和复制，但不会作为 JS 源码执行，也不会加入
   ``require`` 搜索结果。
