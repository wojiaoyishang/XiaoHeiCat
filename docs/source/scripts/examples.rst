脚本示例
==================

本页放置常用脚本模板。更完整的接口说明请阅读 :doc:`js_api`。

Application.onCreate 日志
-------------------------

.. code-block:: javascript

   // ==LSPosedScript==
   // @name         Application.onCreate 日志
   // @id           example.application.oncreate
   // @target       *
   // @process      *
   // @run-at       package-loaded
   // @grant        java.full
   // @grant        xposed.full
   // ==/LSPosedScript==

   xposed.onPackageLoaded(function (param) {
       const Application = Java.use('android.app.Application');
       const onCreate = Java.method(Application, 'onCreate');

       xposed.hook(onCreate).intercept(function (chain) {
           xposed.i('XHH-App', 'before onCreate: ' + param.getPackageName());
           const result = chain.proceed();
           xposed.i('XHH-App', 'after onCreate: ' + param.getPackageName());
           return result;
       });
   });

修改方法参数
-----------------

.. code-block:: javascript

   xposed.hook(targetMethod).intercept(function (chain) {
       const args = chain.getArgsMutable();
       args[0] = 'hooked value';
       return chain.proceed(args);
   });

直接替换返回值
-----------------

.. code-block:: javascript

   xposed.hook(targetMethod).intercept(function () {
       return true;
   });

按设置项控制脚本
-----------------

.. code-block:: javascript

   const enabled = settings.get('enabled', true);
   const tag = settings.get('tag', 'XHH');

   if (!enabled) {
       console.log('script disabled');
       return;
   }

   xposed.i(tag, 'script enabled');

打印应用调用栈
-----------------

.. code-block:: javascript

   xposed.hook(targetMethod).intercept(function (chain) {
       xposed.printAppStackTrace('XHH-Stack', 'targetMethod called');
       return chain.proceed();
   });

多文件脚本
-----------------

目录结构：

.. code-block:: text

   my_script/
   ├─ index.js
   ├─ logger.js
   └─ settings.json

``logger.js``：

.. code-block:: javascript

   exports.i = function (message) {
       xposed.i('XHH', message);
   };

``index.js``：

.. code-block:: javascript

   const logger = require('./logger');

   xposed.onPackageLoaded(function () {
       logger.i('loaded: ' + env.packageName);
   });
