快速开始
==================

准备环境
-----------------

你需要准备：

- 已安装 LSPosed 的 Android 设备。
- 已安装 XiaoHeiHook 管理端应用。
- 已在 LSPosed 中启用 XiaoHeiHook 模块。
- 对需要 Hook 的目标应用启用模块作用域。
- 授予 XiaoHeiHook 管理脚本目录所需的文件权限。

.. important::
   XiaoHeiHook 的脚本目录位于公共文档目录下。部分 Android 版本需要授予“管理所有文件”权限，否则可能无法扫描或保存脚本。

创建脚本目录
-----------------

脚本默认放在：

.. code-block:: text

   Documents/XiaoHeiHook/

单文件脚本示例：

.. code-block:: text

   Documents/XiaoHeiHook/application_log.js

多文件脚本示例：

.. code-block:: text

   Documents/XiaoHeiHook/okhttp_logger/
   ├─ index.js
   ├─ settings.json
   ├─ logger.js
   └─ okhttp.js

第一个脚本
-----------------

新建 ``Documents/XiaoHeiHook/application_log.js``：

.. code-block:: javascript

   // ==LSPosedScript==
   // @name         Application.onCreate 日志
   // @id           demo.application.oncreate.log
   // @version      1.0.0
   // @author       XiaoHeiHook
   // @description  记录目标应用 Application.onCreate
   // @target       *
   // @process      *
   // @run-at       package-loaded
   // @grant        java.full
   // @grant        xposed.full
   // ==/LSPosedScript==

   const TAG = 'XHH-Demo';

   xposed.onPackageLoaded(function (param) {
       const appClass = Java.use('android.app.Application');
       const onCreate = appClass.getDeclaredMethod('onCreate');
       onCreate.setAccessible(true);

       xposed.hook(onCreate).intercept(function (chain) {
           xposed.i(TAG, 'Application.onCreate: ' + param.getPackageName());
           return chain.proceed();
       });
   });

启用脚本
-----------------

1. 打开 XiaoHeiHook。
2. 进入“应用”页面。
3. 选择目标应用。
4. 打开应用开关。
5. 打开脚本开关。
6. 点击同步。
7. 重启目标应用。

查看日志
-----------------

进入目标应用详情页后，打开日志页面即可查看脚本输出。
日志不会自动换行，支持横向滚动、颜色区分和字号缩放。
