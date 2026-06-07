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
   └─ settings.json

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

同步规则
-----------------

多文件脚本同步时会同步：

- ``index.js``
- ``settings.json``
- 其他依赖文件

同步到目标进程后，脚本通过 Remote Files 读取内容。同步文件会带有 SHA-256 校验，避免目标进程读取到不一致的脚本内容。
