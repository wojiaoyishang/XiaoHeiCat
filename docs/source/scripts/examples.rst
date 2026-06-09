脚本示例
==================

本页放置常用脚本模板。更完整的接口说明请阅读 :doc:`js_api`。

.. note::
	示例脚本可以参考仓库：https://github.com/wojiaoyishang/XiaoHeiCat/tree/master/examples 。

Application.onCreate 日志
---------------------------

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
       const Application = Java.type('android.app.Application');
       const onCreate = Application.getDeclaredMethod('onCreate');
       onCreate.setAccessible(true);

       xposed.hook(onCreate).intercept(function (chain) {
           xposed.i('XHH-App', 'before onCreate: ' + param.getPackageName());
           const result = chain.proceed();
           xposed.i('XHH-App', 'after onCreate: ' + param.getPackageName());
           return result;
       });
   });

Application.attach 后 Toast
------------------------------

.. tip::
	完整脚本可以参考 https://github.com/wojiaoyishang/XiaoHeiCat/tree/master/examples/toast.js

.. code-block:: javascript

   // ==LSPosedScript==
   // @name         App 启动后 Toast
   // @id           example.toast.after.launch
   // @target       *
   // @process      *
   // @run-at       package-loaded
   // @grant        java.full
   // @grant        xposed.full
   // ==/LSPosedScript==

   const TAG = 'ToastDemo';
   let executed = false;

   function showToast(context, text) {
       const Toast = Java.type('android.widget.Toast');
       const Looper = Java.type('android.os.Looper');
       const Handler = Java.type('android.os.Handler');

       const mainHandler = new Handler(Looper.getMainLooper());

       mainHandler.post(function () {
           Toast.makeText(context, String(text), Toast.LENGTH_SHORT).show();
       });
   }

   xposed.onPackageLoaded(function (param) {
       const Application = Java.type('android.app.Application');
       const ContextClass = Java.type('android.content.Context');

       const attach = Application.getDeclaredMethod('attach', ContextClass);
       attach.setAccessible(true);

       xposed.hook(attach)
           .setPriority(xposed.PRIORITY_DEFAULT)
           .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
           .intercept(function (chain) {
               const context = chain.getArg(0);
               const result = chain.proceed();

               if (executed) return result;
               executed = true;

               try {
                   const appPackage = String(context.getPackageName());
                   xposed.i(TAG, 'Application.attach after package=' + appPackage);
                   showToast(context, 'XiaoHeiHook 脚本已运行: ' + appPackage);
               } catch (e) {
                   xposed.e(TAG, 'show toast failed', e);
               }

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
   

文件读写：写入目标 App 私有目录
--------------------------------------------

.. note::
   文件路径不要硬编码 ``/data/user/0``。应该在 ``Application.attach(Context)`` 后，
   通过 ``xhh.fs.appDirs(context)`` 获取当前目标 App 的真实私有目录。

.. code-block:: javascript

   const TAG = 'FsReadWriteDemo';
   let executed = false;

   xposed.onPackageLoaded(function () {
       const Application = Java.type('android.app.Application');
       const ContextClass = Java.type('android.content.Context');
       const attach = Application.getDeclaredMethod('attach', ContextClass);
       attach.setAccessible(true);

       xposed.hook(attach).intercept(function (chain) {
           const context = chain.getArg(0);
           const result = chain.proceed();

           if (executed) return result;
           executed = true;

           try {
               const dirs = xhh.fs.appDirs(context);
               const workDir = xhh.fs.join(dirs.filesDir, 'xhh_demo');
               const filePath = xhh.fs.join(workDir, 'hello.txt');

               xhh.fs.mkdirs(workDir);
               xhh.fs.writeText(filePath, 'package=' + dirs.packageName + '\n', 'UTF-8');

               const text = xhh.fs.readText(filePath, 'UTF-8');
               xposed.i(TAG, 'file=' + filePath);
               xposed.i(TAG, 'content=\n' + text);
           } catch (e) {
               xposed.e(TAG, 'fs read/write failed', e);
           }

           return result;
       });
   });

多文件脚本：复制 assets 并展示图片
--------------------------------------------

.. tip::
   完整代码参考 https://github.com/wojiaoyishang/XiaoHeiCat/tree/master/examples/multi_asset_showcase 。

这个示例适合学习 ``require``、``assets/`` 和 ``xhh.fs.copyAssetToApp`` 的组合用法。
脚本启动后会把 ``assets/images/generated-showcase.png`` 复制到目标 App 私有目录，并在
首个 ``Activity.onResume`` 后尝试用 ``ImageView`` 展示。

目录结构::

   demo.multi.asset.showcase/
   ├─ index.js
   ├─ main.js
   ├─ lib/
   │  ├─ assets.js
   │  └─ showcase.js
   └─ assets/
      ├─ data/config.json
      ├─ images/generated-showcase.png
      └─ panel/index.html

``index.js`` 只负责加载入口模块::

   require('./main.js').install();

``lib/assets.js`` 中的核心逻辑::

   function prepare(context) {
       const config = JSON.parse(xhh.fs.readAssetText('data/config.json', 'UTF-8'));

       const options = {
           rootDir: config.targetRootDir,
           overwrite: true,
           clean: false
       };

       const sync = xhh.fs.syncAssetsToApp(context, options);
       const image = xhh.fs.copyAssetToApp(
           context,
           config.imageAsset,
           config.imageTarget,
           options
       );

       return {
           config: config,
           sync: sync,
           image: image,
           targetDir: xhh.fs.appAssetDir(context, options)
       };
   }

``Activity.onResume`` 后展示图片的核心逻辑::

   const ImageView = Java.type('android.widget.ImageView');
   const Uri = Java.type('android.net.Uri');
   const File = Java.type('java.io.File');

   const image = new ImageView(activity);
   image.setAdjustViewBounds(true);
   image.setImageURI(Uri.fromFile(new File(prepared.image.path)));

.. important::
   ``rootDir`` 是相对目标 App ``filesDir`` 的安全子目录，例如
   ``xhh_showcase/demo.multi.asset.showcase``。不要写 ``../``，也不要把资源复制到
   App 私有目录之外。

Hook 某个方法
-----------------

.. tip::
	完整代码参考 https://github.com/wojiaoyishang/XiaoHeiCat/tree/master/examples/qidian.js

.. code-block:: javascript

	const TAG = "QidianHookPcAD";

	const PACKAGE_NAME = "cn.am7code.tools";
	const TARGET_CLASS = "pc.a";
	const TARGET_METHOD = "d";

	let installed = false;

	xposed.onPackageLoaded(function (param) {
	  const pkg = String(param.getPackageName());
	  if (pkg !== PACKAGE_NAME) return;

	  xposed.i(TAG, "script loaded package=" + pkg + " process=" + env.processName);

	  const Application = Java.type("android.app.Application");
	  const ContextClass = Java.type("android.content.Context");

	  const attach = Application.getDeclaredMethod("attach", ContextClass);
	  attach.setAccessible(true);

	  xposed
		.hook(attach)
		.setPriority(xposed.PRIORITY_DEFAULT)
		.setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
		.intercept(function (chain) {
		  const context = chain.getArg(0);
		  const result = chain.proceed();

		  try {
			const loader = context.getClassLoader();
			installHook(loader);
		  } catch (e) {
			xposed.e(TAG, "install hook failed", e);
		  }

		  return result;
		});
	});

	function installHook(loader) {
	  if (installed) {
		xposed.d(TAG, "hook already installed, skip");
		return;
	  }

	  const TargetClass = loader.loadClass(TARGET_CLASS);
	  const method = TargetClass.getDeclaredMethod(TARGET_METHOD);
	  method.setAccessible(true);

	  xposed
		.hook(method)
		.setPriority(xposed.PRIORITY_DEFAULT)
		.setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
		.intercept(function (chain) {
		  xposed.d(TAG, "pc.a.d before");
		  xposed.d(TAG, "executable=" + chain.getExecutable());
		  xposed.d(TAG, "thisObject=" + chain.getThisObject());

		  let oldResult = false;
		  try {
			oldResult = chain.proceed();
			xposed.d(TAG, "pc.a.d original result=" + oldResult);
		  } catch (e) {
			xposed.e(TAG, "pc.a.d original call failed", e);
		  }

		  xposed.i(TAG, "pc.a.d override result=true");
		  return true;
		});

	  installed = true;
	  xposed.i(TAG, "Hook installed: pc.a.d()Z");
	}

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


.. _动态DEX:

动态 DEX 脱壳
-------------------------------

所有 DumpDex 示例都必须声明：

.. code-block:: javascript

   // @grant        dex.dump

如果要在 dump 后继续搜索方法，还需要声明：

.. code-block:: javascript

   // @grant        dex.find

脱壳：dumpDexCookies
~~~~~~~~~~~~~~~~~~~~~~~~

.. tip::
	完整脚本可以参考 https://github.com/wojiaoyishang/XiaoHeiCat/tree/master/examples/dumpdex.js

``dumpDexCookies`` 在 ``Application.attach`` 后使用真实 app ClassLoader dump 当前已加载 dex。
默认输出目录为 ``/data/user/0/<package>/code_cache/xhh_dumpdex``，默认文件名以
``cookie_`` 开头，格式为 ``cookie_<index>_<addressHex>_<fileSize>.dex``。
脚本逻辑应读取 ``ret.paths``，不要自行扫描目录。

.. code-block:: javascript

   // ==LSPosedScript==
   // @name         通用脱壳 DumpDex
   // @id           generic.dumpdex.only
   // @version      1.1.0
   // @description  Application.attach 后 dump 当前加载的全部 dex。
   // @target       *
   // @process      *
   // @run-at       package-loaded
   // @grant        java.full
   // @grant        xposed.full
   // @grant        dex.dump
   // ==/LSPosedScript==

   const TAG = 'DumpDexOnly';
   let executed = false;

   xposed.onPackageLoaded(function (param) {
       const processName = String(env.processName || '');
       const Application = Java.type('android.app.Application');
       const ContextClass = Java.type('android.content.Context');
       const attach = Application.getDeclaredMethod('attach', ContextClass);
       attach.setAccessible(true);

       xposed.hook(attach)
           .setPriority(xposed.PRIORITY_DEFAULT)
           .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
           .intercept(function (chain) {
               const context = chain.getArg(0);
               const result = chain.proceed();

               try {
                   if (executed) return result;
                   executed = true;

                   const appPackage = String(context.getPackageName());
                   const loader = context.getClassLoader();
                   const outputDir = '/data/user/0/' + appPackage + '/code_cache/xhh_dumpdex';

                   const ret = dex.dumpDexCookies({
                       loader: loader,
                       outputDir: outputDir,
                       clearOutputDir: true,
                       clearCookieDir: true,
                       maxDexBytes: 512 * 1024 * 1024
                   });

                   if (!ret.ok) {
                       xposed.e(TAG, 'dump failed ret=' + JSON.stringify(ret));
                       return result;
                   }

                   xposed.i(TAG, 'dump finished count=' + ret.count);
                   xposed.i(TAG, 'outputDir=' + ret.outputDir);

                   xhh.each(ret.paths || [], function (path, i) {
                       xposed.i(TAG, 'dumped[' + i + ']=' + path);
                       return true;
                   });
               } catch (e) {
                   xposed.e(TAG, 'dump failed', e);
               }

               return result;
           });
   });

DumpDex 后进行 Smali 特征查找
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. tip::
	完整脚本可以参考 https://github.com/wojiaoyishang/XiaoHeiCat/tree/master/examples/smali_search.js

推荐使用 ``xhh.each(paths, callback)`` 遍历 dump 结果。callback 返回 ``false`` 会停止遍历，
适合“找到第一个目标后立即结束”的场景。

.. code-block:: javascript

   const TAG = 'DumpDexSmaliSearch';
   let executed = false;

   const SMALI_FEATURES = [
       'const-string "am7_dev_vip_override"',
       'Lcom/blankj/utilcode/util/r;->a(Ljava/lang/String;)Lcom/blankj/utilcode/util/r;',
       'Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;',
       'const-string "vip"',
       'const-string "nonvip"',
       'Ljava/lang/System;->currentTimeMillis()J'
   ];

   function dumpDexAndSearch(loader, appPackage, processName) {
       const outputDir = '/data/user/0/' + appPackage + '/code_cache/xhh_dumpdex';
       const dumpRet = dex.dumpDexCookies({
           loader: loader,
           outputDir: outputDir,
           clearOutputDir: true,
           clearCookieDir: true,
           maxDexBytes: 512 * 1024 * 1024
       });

       if (!dumpRet.ok) {
           xposed.e(TAG, 'dumpDexCookies failed ret=' + JSON.stringify(dumpRet));
           return;
       }

       let found = null;
       const paths = dumpRet.paths || [];

       xhh.each(paths, function (path, i) {
           xposed.i(TAG, 'search smali in dex[' + i + ']=' + path);

           const results = dex.findMethods({
               path: path,
               smaliContains: SMALI_FEATURES,
               limit: 1,
               includeSmali: true
           });

           if (results.length === 0) {
               xposed.d(TAG, 'no match in dex[' + i + ']');
               return true;
           }

           const m = results[0];
           found = {
               dexIndex: i,
               dexPath: path,
               className: m.className,
               methodName: m.methodName,
               proto: m.proto,
               descriptor: m.descriptor
           };
           return false;
       });

       if (found) {
           xposed.i(TAG, '========== SMALI SEARCH SUMMARY ==========');
           xposed.i(TAG, 'foundDexIndex=' + found.dexIndex);
           xposed.i(TAG, 'foundDexPath=' + found.dexPath);
           xposed.i(TAG, 'foundClassName=' + found.className);
           xposed.i(TAG, 'foundMethodName=' + found.methodName);
           xposed.i(TAG, 'foundProto=' + found.proto);
       } else {
           xposed.w(TAG, 'no dex matched the given smali features, searched=' + paths.length);
       }
   }

   xposed.onPackageLoaded(function (param) {
       const processName = String(env.processName || '');
       const Application = Java.type('android.app.Application');
       const ContextClass = Java.type('android.content.Context');
       const attach = Application.getDeclaredMethod('attach', ContextClass);
       attach.setAccessible(true);

       xposed.hook(attach)
           .setPriority(xposed.PRIORITY_DEFAULT)
           .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
           .intercept(function (chain) {
               const context = chain.getArg(0);
               const result = chain.proceed();

               try {
                   if (executed) return result;
                   executed = true;
                   dumpDexAndSearch(context.getClassLoader(), String(context.getPackageName()), processName);
               } catch (e) {
                   xposed.e(TAG, 'dump and search failed', e);
               }

               return result;
           });
   });

精确检查方法：inspectMethodInFile
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

``inspectMethodInFile`` 不做搜索，只检查指定 ``path + className + methodName`` 是否存在，
以及字符串、invoke、smali 特征是否满足。

.. code-block:: javascript

   const inspected = dex.inspectMethodInFile({
       path: found.dexPath,
       className: found.className,
       methodName: found.methodName,
       proto: found.proto,
       smaliContains: SMALI_FEATURES,
       includeSmali: true
   });

   xposed.i(TAG, 'featuresOk=' + inspected.featuresOk);
