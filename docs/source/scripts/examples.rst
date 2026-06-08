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

脱壳
~~~~~~~~~~~~~~~~~~~~~~~~

.. tip:
	完整脚本参考 https://github.com/wojiaoyishang/XiaoHeiCat/tree/master/examples/xhh_dumpdex.js

核心逻辑：

.. code-block:: javascript

	xposed.onPackageLoaded(function (param) {
	  const packageName = String(param.getPackageName());
	  const processName = String(env.processName || "");

	  xposed.i(TAG, "loaded package=" + packageName + " process=" + processName);

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

		  xposed.d(TAG, "Application.attach before");

		  const result = chain.proceed();

		  try {
			if (executed) {
			  xposed.d(TAG, "dump already executed, skip");
			  return result;
			}

			executed = true;

			const appPackage = String(context.getPackageName());
			const loader = context.getClassLoader();

			xposed.i(TAG, "Application.attach after");
			xposed.i(TAG, "package=" + appPackage);
			xposed.i(TAG, "process=" + processName);
			xposed.i(TAG, "appClassLoader=" + loader);

			const outputDir =
			  "/data/user/0/" +
			  appPackage +
			  "/code_cache/xhh_dumpdex";

			xposed.i(TAG, "dump all dex start outputDir=" + outputDir);

			const ret = dex.dumpDexCookies({
			  loader: loader,
			  cookieDir: outputDir,
			  outputDir: outputDir,
			  clearCookieDir: true,
			  clearOutputDir: true,
			  maxDexBytes: 512 * 1024 * 1024
			});

			xposed.i(TAG, "dump all dex finished result=" + ret);
			xposed.i(TAG, "dex output dir=" + outputDir);
		  } catch (e) {
			xposed.e(TAG, "dump all dex failed", e);
		  }

		  return result;
		});
	});

等待某方法加载后再脱壳
~~~~~~~~~~~~~~~~~~~~~~~~


.. code-block:: javascript

	const TAG = "WaitMethodDumpDex";

	// 修改这里
	const TARGET_CLASS = "pc.a";
	const TARGET_METHOD = "d";
	const TARGET_METHOD_PARAM_TYPES = []; // 例如 ["java.lang.String", "int"]

	let dumped = false;

	xposed.onPackageLoaded(function (param) {
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
			xposed.i(TAG, "appClassLoader=" + loader);

			// 先尝试一次
			if (tryDumpWhenMethodReady(context, loader, "Application.attach")) {
			  return result;
			}

			// 如果 attach 阶段还没准备好，则 hook ClassLoader.loadClass 等待目标类出现
			installLoadClassWatcher(context, loader);
		  } catch (e) {
			xposed.e(TAG, "setup dump failed", e);
		  }

		  return result;
		});
	});

	function installLoadClassWatcher(context, appLoader) {
	  try {
		const ClassLoader = Java.type("java.lang.ClassLoader");
		const StringClass = Java.type("java.lang.String");

		const loadClass = ClassLoader.getDeclaredMethod("loadClass", StringClass);
		loadClass.setAccessible(true);

		xposed
		  .hook(loadClass)
		  .setPriority(xposed.PRIORITY_LOWEST)
		  .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
		  .intercept(function (chain) {
			const name = String(chain.getArg(0));
			const result = chain.proceed();

			if (!dumped && name === TARGET_CLASS) {
			  xposed.i(TAG, "target class loaded via ClassLoader.loadClass: " + name);
			  tryDumpWhenMethodReady(context, appLoader, "ClassLoader.loadClass");
			}

			return result;
		  });

		xposed.i(TAG, "installed ClassLoader.loadClass watcher for " + TARGET_CLASS);
	  } catch (e) {
		xposed.e(TAG, "install loadClass watcher failed", e);
	  }
	}

	function tryDumpWhenMethodReady(context, loader, reason) {
	  if (dumped) return true;

	  try {
		const clazz = loader.loadClass(TARGET_CLASS);
		const method = getDeclaredMethod(clazz, TARGET_METHOD, TARGET_METHOD_PARAM_TYPES);

		if (method === null) {
		  xposed.d(TAG, "class ready but method not found yet: " + TARGET_CLASS + "." + TARGET_METHOD);
		  return false;
		}

		method.setAccessible(true);
		xposed.i(TAG, "target method ready reason=" + reason + " method=" + method);

		dumped = true;

		const packageName = String(context.getPackageName());
		const outputDir =
		  "/data/user/0/" + packageName + "/code_cache/xhh_dumpdex";

		xposed.i(TAG, "dump start outputDir=" + outputDir);

		const ret = dex.dumpDexCookies({
		  loader: loader,
		  outputDir: outputDir,
		  cookieDir: outputDir,
		  clearOutputDir: true,
		  maxDexBytes: 512 * 1024 * 1024
		});

		xposed.i(TAG, "dump finished result=" + ret);
		xposed.i(TAG, "dex output dir=" + outputDir);

		return true;
	  } catch (e) {
		xposed.d(TAG, "target method not ready reason=" + reason + " err=" + e);
		return false;
	  }
	}

	function getDeclaredMethod(clazz, name, paramTypeNames) {
	  try {
		const ArrayReflect = Java.type("java.lang.reflect.Array");
		const ClassClass = Java.type("java.lang.Class");

		const params = ArrayReflect.newInstance(ClassClass, paramTypeNames.length);

		for (let i = 0; i < paramTypeNames.length; i++) {
		  ArrayReflect.set(params, i, resolveParamClass(paramTypeNames[i]));
		}

		return clazz.getDeclaredMethod(name, params);
	  } catch (e) {
		return null;
	  }
	}

	function resolveParamClass(typeName) {
	  if (typeName === "boolean") return java.lang.Boolean.TYPE;
	  if (typeName === "byte") return java.lang.Byte.TYPE;
	  if (typeName === "char") return java.lang.Character.TYPE;
	  if (typeName === "short") return java.lang.Short.TYPE;
	  if (typeName === "int") return java.lang.Integer.TYPE;
	  if (typeName === "long") return java.lang.Long.TYPE;
	  if (typeName === "float") return java.lang.Float.TYPE;
	  if (typeName === "double") return java.lang.Double.TYPE;
	  if (typeName === "void") return java.lang.Void.TYPE;

	  return Java.type(typeName).class;
	}
	
在 DEX 寻找代码特征
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. tip:
	完整脚本参考 https://github.com/wojiaoyishang/XiaoHeiCat/tree/master/examples/qidian_dex_locate.js


目标代码的 ASM 如下：

.. code-block:: text
	
	.registers 8
		#@0  const-string ""
		#@2  invoke-static Lcom/blankj/utilcode/util/r;->a(Ljava/lang/String;)Lcom/blankj/utilcode/util/r;
		#@5  move-result-object
		#@6  iget-object Lcom/blankj/utilcode/util/r;->a:Landroid/content/SharedPreferences;
		#@8  const-string "am7_dev_vip_override"
		#@a  invoke-interface Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
		#@d  move-result-object
		#@e  const-string "getString(...)"
		#@10  invoke-static Lkotlin/jvm/internal/k;->f(Ljava/lang/Object;Ljava/lang/String;)V
		#@13  const-string "vip"
		#@15  invoke-virtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z
		#@18  move-result
		#@19  if-eqz
		#@1b  goto
		#@1c  const-string "nonvip"
		#@1e  invoke-virtual Ljava/lang/Object;->equals(Ljava/lang/Object;)Z
		#@21  move-result
		#@22  if-eqz
		#@24  goto
		#@25  invoke-static Lpc/a;->c()Z
		#@28  move-result
		#@29  if-nez
		#@2b  goto
		#@2c  invoke-static Lpc/a;->b()Lwork/am7code/common/userinfo/model/Am7UserInfo;
		#@2f  move-result-object
		#@30  invoke-static Lkotlin/jvm/internal/k;->d(Ljava/lang/Object;)V
		#@33  invoke-virtual Lwork/am7code/common/userinfo/model/Am7UserInfo;->getVip_start_time()Ljava/lang/Long;
		#@36  move-result-object
		#@37  invoke-virtual Lwork/am7code/common/userinfo/model/Am7UserInfo;->getVip_duration()Ljava/lang/Long;
		#@3a  move-result-object
		#@3b  if-eqz
		#@3d  if-nez
		#@3f  goto
		#@40  invoke-virtual Ljava/lang/Long;->longValue()J
		#@43  move-result-wide
		#@44  invoke-virtual Ljava/lang/Long;->longValue()J
		#@47  move-result-wide
		#@48  add-long/2addr
		#@49  invoke-static Ljava/lang/System;->currentTimeMillis()J
		#@4c  move-result-wide
		#@4d  const/16
		#@4f  int-to-long
		#@50  div-long/2addr
		#@51  invoke-virtual Ljava/lang/Long;->longValue()J
		#@54  move-result-wide
		#@55  cmp-long
		#@57  if-gtz
		#@59  cmp-long
		#@5b  if-gtz
		#@5d  const/4
		#@5e  return
		#@5f  const/4
		#@60  return
	.end method

可以通过如下代码简单定位：

.. code-block:: javascript

	const TAG = "QidianLocatePcAD";

	const PACKAGE_NAME = "cn.am7code.tools";
	const TARGET_CLASS = "pc.a";
	const TARGET_METHOD = "d";
	const TARGET_PROTO = "()Z";

	const FEATURES = [
	  "am7_dev_vip_override",
	  "getString(...)",
	  "vip",
	  "nonvip"
	];

	const INVOKE_CONTAINS = [
	  "Landroid/content/SharedPreferences;->getString(",
	  "Lwork/am7code/common/userinfo/model/Am7UserInfo;->getVip_start_time(",
	  "Lwork/am7code/common/userinfo/model/Am7UserInfo;->getVip_duration(",
	  "Ljava/lang/System;->currentTimeMillis("
	];

	let executed = false;

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

		  if (executed) return result;
		  executed = true;

		  try {
			const loader = context.getClassLoader();
			const dumpDir =
			  "/data/user/0/" + PACKAGE_NAME + "/code_cache/xhh_dumpdex";

			xposed.i(TAG, "appClassLoader=" + loader);
			xposed.i(TAG, "dumpDir=" + dumpDir);

			// 1. 获取运行时 Java Method 对象
			const TargetClass = loader.loadClass(TARGET_CLASS);
			const runtimeMethod = TargetClass.getDeclaredMethod(TARGET_METHOD);
			runtimeMethod.setAccessible(true);

			xposed.i(TAG, "runtime Method=" + runtimeMethod);
			xposed.i(TAG, "declaringClass=" + runtimeMethod.getDeclaringClass());
			xposed.i(TAG, "returnType=" + runtimeMethod.getReturnType());

			// 2. 在已脱壳 dex 目录中定位 pc.a.d()Z 所在 dex
			const located = dex.locateMethodInCookieDumps({
			  dir: dumpDir,
			  prefix: "cookie_",
			  className: TARGET_CLASS,
			  methodName: TARGET_METHOD,
			  proto: TARGET_PROTO,
			  maxDexBytes: 512 * 1024 * 1024
			});

			const found = boolGet(located, "found");
			xposed.i(TAG, "locate found=" + found + " raw=" + located);

			if (!found) {
			  xposed.w(TAG, "pc.a.d()Z not found in dump dir=" + dumpDir);
			  return result;
			}

			const dexPath = strGet(located, "path");
			xposed.i(TAG, "target dex path=" + dexPath);

			// 3. 检查该方法的字符串 / invoke 特征
			const inspected = dex.inspectMethodInFile({
			  path: dexPath,
			  className: TARGET_CLASS,
			  methodName: TARGET_METHOD,
			  proto: TARGET_PROTO,
			  strings: FEATURES,
			  invokeContains: INVOKE_CONTAINS,
			  maxDexBytes: 512 * 1024 * 1024
			});

			xposed.i(TAG, "inspect found=" + boolGet(inspected, "found"));
			xposed.i(TAG, "classFound=" + boolGet(inspected, "classFound"));
			xposed.i(TAG, "methodFound=" + boolGet(inspected, "methodFound"));
			xposed.i(TAG, "featuresOk=" + boolGet(inspected, "featuresOk"));
			xposed.i(TAG, "descriptor=" + strGet(inspected, "descriptor"));
			xposed.i(TAG, "static=" + boolGet(inspected, "static"));

			xposed.i(TAG, "strings=" + strGet(inspected, "strings"));
			xposed.i(TAG, "invokes=" + strGet(inspected, "invokes"));
			xposed.i(TAG, "missingStrings=" + strGet(inspected, "missingStrings"));
			xposed.i(TAG, "missingInvokeContains=" + strGet(inspected, "missingInvokeContains"));
			xposed.i(TAG, "smali.head=" + strGet(inspected, "smaliHead"));

			// 这里 runtimeMethod 就是你要的 Java 反射 Method 对象
			// inspected 是 dex 方法体分析结果
		  } catch (e) {
			xposed.e(TAG, "locate pc.a.d failed", e);
		  }

		  return result;
		});
	});

	function mapGet(map, key) {
	  try {
		if (map === null || map === undefined) return null;
		return map.get(String(key));
	  } catch (e) {
		return null;
	  }
	}

	function strGet(map, key) {
	  const v = mapGet(map, key);
	  if (v === null || v === undefined) return "";
	  return String(v);
	}

	function boolGet(map, key) {
	  const v = mapGet(map, key);
	  if (v === null || v === undefined) return false;
	  return String(v) === "true";
	}