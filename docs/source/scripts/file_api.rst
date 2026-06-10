文件与资源接口
================================================================================

``xhh.fs`` 是 ``1.30 (107)`` 新增的文件与资源桥接 API，用来解决三类常见需求：

1. 在目标 App 私有目录中读写脚本自己的工作文件。
2. 读取当前脚本目录下 ``assets/`` 中的文本、图片、HTML、JSON 等资源。
3. 把 ``assets/`` 资源复制到目标 App 私有目录，然后交给 ``ImageView``、``WebView``、
   配置加载逻辑或目标 App 自己的 Java API 使用。

源码位置
--------------------------------------------------------------------------------

.. list-table:: ``xhh.fs`` 相关源码
   :header-rows: 1
   :widths: 36 64

   * - 源码文件
     - 说明
   * - ``app/src/main/java/top/lovepikachu/XiaoHeiHook/script/FileJsApi.java``
     - ``xhh.fs`` 对外 facade，负责注入 JS API、解析 options、稳定化返回值。
   * - ``app/src/main/java/top/lovepikachu/XiaoHeiHook/script/TargetAppPathHelper.java``
     - 通过目标 App ``Context`` 动态获取私有目录，避免硬编码 ``/data/user/0``。
   * - ``app/src/main/java/top/lovepikachu/XiaoHeiHook/script/ScriptPathResolver.java``
     - 解析当前脚本根目录、脚本目录和 ``assets/`` 目录。
   * - ``app/src/main/java/top/lovepikachu/XiaoHeiHook/script/ScriptAssetManager.java``
     - 校验 asset 相对路径、读取 asset、复制或同步 asset 到目标 App 私有目录。
   * - ``app/src/main/java/top/lovepikachu/XiaoHeiHook/script/FileCopyUtils.java``
     - 文件复制、读写、SHA-256、递归清理等工具函数。

.. note::

   ``xhh.fs`` 不使用单独 grant。普通脚本仍按原来的方式声明 ``java.full`` 和
   ``xposed.full``。后续如果拆分文件权限，文档会单独说明。

.. important::

   目标 App 私有目录必须从 ``Context`` 动态获取。不要在脚本中拼接
   ``/data/user/0/<包名>``、``/data/data/<包名>`` 或外部存储绝对路径。
   多用户、工作资料夹、Clone App 等场景下，这些路径都可能不是当前进程真实路径。

路径模型
--------------------------------------------------------------------------------

脚本资源的推荐目录结构如下：

.. code-block:: text

   Documents/XiaoHeiHook/
   └─ demo.multi.asset.showcase/
      ├─ index.js
      ├─ main.js
      ├─ lib/
      │  ├─ assets.js
      │  └─ showcase.js
      └─ assets/
         ├─ data/config.json
         ├─ images/generated-showcase.png
         └─ panel/index.html

``xhh.fs`` 只允许通过资源 API 访问当前脚本自己的 ``assets/`` 目录。
例如 ``readAssetText('data/config.json')`` 会解析为当前脚本的
``assets/data/config.json``，不能读取 ``../index.js`` 或其他脚本目录。

.. tip::

   资源给目标 App 使用时，推荐先复制到目标 App 私有目录，再把复制后的完整路径交给
   ``ImageView``、``WebView`` 或业务代码。不要让目标 App 直接读取脚本根目录。

目标 App 私有目录
--------------------------------------------------------------------------------

.. function:: xhh.fs.appDirs(context)

   返回目标 App 的私有目录信息。目录来自传入的 Android ``Context``，不会硬编码用户 ID。

   :param context: Android ``android.content.Context`` 对象，通常来自
                   ``Application.attach(Context)`` 的第一个参数。
   :return: JS 普通对象。

   返回字段：

   .. list-table:: 返回字段
      :header-rows: 1
      :widths: 32 68

      * - 字段
        - 说明
      * - ``packageName``
        - 目标 App 包名。
      * - ``dataDir``
        - 目标 App 数据目录。低版本 Android 会从 ``filesDir`` 父目录兼容推导。
      * - ``filesDir``
        - ``context.getFilesDir()``。
      * - ``cacheDir``
        - ``context.getCacheDir()``。
      * - ``codeCacheDir``
        - ``context.getCodeCacheDir()``；低版本或不可用时为 ``null``。
      * - ``noBackupFilesDir``
        - ``context.getNoBackupFilesDir()``；低版本或不可用时为 ``null``。
      * - ``externalFilesDir``
        - ``context.getExternalFilesDir(null)``；不可用时为 ``null``。
      * - ``externalCacheDir``
        - ``context.getExternalCacheDir()``；不可用时为 ``null``。
      * - ``deviceProtectedDataDir``
        - Device Protected Storage 数据目录；不可用时为 ``null``。
      * - ``deviceProtectedFilesDir``
        - Device Protected Storage files 目录；不可用时为 ``null``。

   **示例：**

   .. code-block:: javascript

      const dirs = xhh.fs.appDirs(context);
      xposed.i(TAG, 'package=' + dirs.packageName);
      xposed.i(TAG, 'filesDir=' + dirs.filesDir);

基础路径和文件操作
--------------------------------------------------------------------------------

.. function:: xhh.fs.join(...parts)

   拼接路径片段。

   :param parts: 任意数量的路径片段。
   :return: 拼接后的路径字符串。

   ``null``、``undefined`` 会抛出异常，空字符串会被跳过。

   **示例：**

   .. code-block:: javascript

      const file = xhh.fs.join(dirs.filesDir, 'xhh_demo', 'hello.txt');

.. function:: xhh.fs.exists(path)

   判断路径是否存在。

   :param string path: 文件或目录路径。
   :return: ``boolean``。

.. function:: xhh.fs.isFile(path)

   判断路径是否为普通文件。

   :param string path: 文件路径。
   :return: ``boolean``。

.. function:: xhh.fs.isDirectory(path)

   判断路径是否为目录。

   :param string path: 目录路径。
   :return: ``boolean``。

.. function:: xhh.fs.mkdirs(path)

   创建目录及其父目录。

   :param string path: 目录路径。
   :return: 成功时返回 ``true``。

   如果路径已存在但不是目录，会抛出异常。

.. function:: xhh.fs.readText(path, charset='UTF-8', options=null)

   读取文本文件。

   :param string path: 文件路径。
   :param string charset: 字符集，默认 ``UTF-8``。
   :param object options: 可选参数。
   :return: 文本内容。

   ``options`` 支持：

   .. list-table:: options
      :header-rows: 1
      :widths: 28 72

      * - 字段
        - 说明
      * - ``maxBytes``
        - 最大读取字节数，默认 ``16 * 1024 * 1024``。小于等于 0 时使用默认值。

   **示例：**

   .. code-block:: javascript

      const text = xhh.fs.readText(filePath, 'UTF-8', {
          maxBytes: 1024 * 1024
      });

.. function:: xhh.fs.writeText(path, text, charset='UTF-8')

   覆盖写入文本文件，会自动创建父目录。

   :param string path: 文件路径。
   :param string text: 文本内容。``null`` 会按空字符串写入。
   :param string charset: 字符集，默认 ``UTF-8``。
   :return: ``{ path, bytes, append }``。

   **示例：**

   .. code-block:: javascript

      const ret = xhh.fs.writeText(filePath, 'hello\n', 'UTF-8');
      xposed.i(TAG, 'written=' + ret.path + ', bytes=' + ret.bytes);

.. function:: xhh.fs.appendText(path, text, charset='UTF-8')

   追加写入文本文件，会自动创建父目录。

   :param string path: 文件路径。
   :param string text: 追加文本。
   :param string charset: 字符集，默认 ``UTF-8``。
   :return: ``{ path, bytes, append }``，其中 ``append`` 为 ``true``。

.. function:: xhh.fs.readBytes(path, options=null)

   读取二进制文件。

   :param string path: 文件路径。
   :param object options: 支持 ``maxBytes``，默认 ``16MB``。
   :return: JS 侧可遍历的字节数组。

.. function:: xhh.fs.writeBytes(path, bytes)

   覆盖写入二进制文件，会自动创建父目录。

   :param string path: 文件路径。
   :param bytes: 支持 Java ``byte[]``、JS number 数组、Java 数组或 Iterable。
   :return: ``{ path, bytes }``。

.. function:: xhh.fs.copy(src, dst, options=null)

   复制普通文件。

   :param string src: 源文件路径，必须是普通文件。
   :param string dst: 目标文件路径。
   :param object options: 可选参数。
   :return: ``{ source, path, bytes, copied, skipped, overwritten }``。

   ``options`` 支持：

   .. list-table:: options
      :header-rows: 1
      :widths: 28 72

      * - 字段
        - 说明
      * - ``overwrite``
        - 是否覆盖已有目标文件，默认 ``true``。

   .. warning::

      ``copy`` 是普通文件复制接口，不会自动限制源文件必须来自 ``assets/``。
      脚本资源复制应优先使用 ``copyAssetToApp`` 或 ``syncAssetsToApp``。

当前脚本目录
--------------------------------------------------------------------------------

.. function:: xhh.fs.scriptRoot()

   返回当前配置的脚本根目录。

   :return: 绝对路径字符串。默认是当前用户 ``Documents/XiaoHeiHook``。

   脚本根目录可以由管理端设置项修改。当前实现支持 ``scriptRoot`` 或
   ``script.root`` 这类设置键，目标进程内脚本通常只读取结果，不负责修改设置。

.. function:: xhh.fs.scriptDir()

   返回当前脚本所在目录。

   :return: 绝对路径字符串。单文件脚本返回脚本文件父目录；多文件脚本返回脚本包目录。

.. function:: xhh.fs.assetsDir()

   返回当前脚本 ``assets/`` 目录。

   :return: 绝对路径字符串。目录不存在时也会返回预期路径，读取资源时才会报错。

.. function:: xhh.fs.assetPath(relativePath)

   解析当前脚本 ``assets/`` 下的安全资源路径。

   :param string relativePath: ``assets/`` 内的相对路径，例如 ``images/icon.png``。
   :return: 解析后的绝对路径字符串。

   ``relativePath`` 不能是绝对路径，不能包含 NUL 字符，不能通过 ``../`` 逃逸。
   Java 层会使用 canonical path 校验最终路径仍在当前脚本 ``assets/`` 目录内。

   **示例：**

   .. code-block:: javascript

      const icon = xhh.fs.assetPath('images/icon.png');
      xposed.i(TAG, 'icon=' + icon);

读取当前脚本 assets
--------------------------------------------------------------------------------

.. function:: xhh.fs.readAssetText(relativePath, charset='UTF-8', options=null)

   读取当前脚本 ``assets/`` 下的文本资源。

   :param string relativePath: ``assets/`` 内相对路径。
   :param string charset: 字符集，默认 ``UTF-8``。
   :param object options: 支持 ``maxBytes``，默认 ``16MB``。
   :return: 文本内容。

   **示例：**

   .. code-block:: javascript

      const configText = xhh.fs.readAssetText('data/config.json', 'UTF-8');
      const config = JSON.parse(configText);

.. function:: xhh.fs.readAssetBytes(relativePath, options=null)

   读取当前脚本 ``assets/`` 下的二进制资源。

   :param string relativePath: ``assets/`` 内相对路径。
   :param object options: 支持 ``maxBytes``，默认 ``16MB``。
   :return: JS 侧可遍历的字节数组。

   **示例：**

   .. code-block:: javascript

      const bytes = xhh.fs.readAssetBytes('images/icon.png');
      xposed.i(TAG, 'icon bytes=' + bytes.length);

复制 assets 到目标 App 私有目录
--------------------------------------------------------------------------------

.. function:: xhh.fs.appAssetDir(context, options=null)

   返回当前脚本在目标 App 私有目录中的 asset 目标根目录，但不执行复制。

   :param context: Android ``Context``。
   :param object options: 目标目录选项。
   :return: 绝对路径字符串。

   ``options`` 支持：

   .. list-table:: options
      :header-rows: 1
      :widths: 28 72

      * - 字段
        - 说明
      * - ``rootDir``
        - 目标根目录。可以是相对 ``filesDir`` 的安全子路径，也可以是位于
          ``filesDir`` 内的绝对路径。
      * - ``versioned``
        - 未指定 ``rootDir`` 时是否追加脚本版本目录，默认 ``true``。
      * - ``base``
        - 保留字段，当前只支持 ``files``。传入其他值会抛出异常。

   未指定 ``rootDir`` 时，默认目录为：

   .. code-block:: text

      <context.getFilesDir()>/xhh_assets/<scriptId>/<version>/

   指定 ``rootDir`` 时，目录必须仍在目标 App ``filesDir`` 内。

.. function:: xhh.fs.copyAssetToApp(context, assetRelativePath, targetRelativePath=null, options=null)

   复制当前脚本 ``assets/`` 下的单个文件到目标 App 私有目录。

   :param context: Android ``Context``。
   :param string assetRelativePath: ``assets/`` 内源资源相对路径。
   :param string targetRelativePath: 目标根目录下的相对路径。省略时等于
                                     ``assetRelativePath``。
   :param object options: 复制选项。
   :return: ``{ asset, path, baseDir, bytes, sha256, copied, skipped, overwritten }``。

   ``options`` 支持：

   .. list-table:: options
      :header-rows: 1
      :widths: 28 72

      * - 字段
        - 说明
      * - ``rootDir``
        - 复制根目录。相对路径会从目标 App ``filesDir`` 开始解析。
      * - ``overwrite``
        - 是否覆盖已有文件，默认 ``true``。
      * - ``versioned``
        - 未指定 ``rootDir`` 时是否使用 ``<scriptId>/<version>``，默认 ``true``。
      * - ``maxBytes``
        - 单个 asset 最大读取字节数，默认 ``16MB``。
      * - ``base``
        - 保留字段，当前只支持 ``files``。

   **示例：**

   .. code-block:: javascript

      const copied = xhh.fs.copyAssetToApp(
          context,
          'images/icon.png',
          'images/icon.png',
          {
              rootDir: 'xhh_showcase/demo.multi.asset.showcase',
              overwrite: true
          }
      );

      xposed.i(TAG, 'copied=' + copied.path);

   .. important::

      ``targetRelativePath`` 不能使用 ``../`` 逃逸。即使传入绝对路径，最终路径也必须同时位于：

      1. 目标 App ``filesDir`` 内；
      2. 本次 asset 目标根目录 ``baseDir`` 内。

.. function:: xhh.fs.syncAssetsToApp(context, options=null)

   递归同步当前脚本 ``assets/`` 下的全部普通文件到目标 App 私有目录。

   :param context: Android ``Context``。
   :param object options: 同步选项。
   :return: ``{ sourceDir, targetDir, copied, skipped, overwritten, deleted, files }``。

   ``options`` 支持：

   .. list-table:: options
      :header-rows: 1
      :widths: 28 72

      * - 字段
        - 说明
      * - ``rootDir``
        - 同步目标根目录。相对路径会从目标 App ``filesDir`` 开始解析。
      * - ``overwrite``
        - 默认 ``true``。也可以传 ``false`` 或 ``'changed'``。
      * - ``clean``
        - 是否删除目标根目录中已经不存在于当前 ``assets/`` 的旧文件，默认 ``false``。
      * - ``versioned``
        - 未指定 ``rootDir`` 时是否使用版本目录，默认 ``true``。
      * - ``maxBytes``
        - 单个 asset 最大读取字节数，默认 ``16MB``。
      * - ``base``
        - 保留字段，当前只支持 ``files``。

   ``overwrite`` 行为：

   .. list-table:: overwrite 行为
      :header-rows: 1
      :widths: 28 72

      * - 值
        - 行为
      * - ``true``
        - 默认值，每次都覆盖写入。
      * - ``false``
        - 目标文件存在时跳过。
      * - ``'changed'``
        - 目标文件大小或 SHA-256 不一致时才复制。

   **示例：**

   .. code-block:: javascript

      const result = xhh.fs.syncAssetsToApp(context, {
          rootDir: 'xhh_showcase/demo.multi.asset.showcase',
          overwrite: true,
          clean: false
      });

      xposed.i(TAG, 'targetDir=' + result.targetDir);
      xposed.i(TAG, 'copied=' + result.copied + ', skipped=' + result.skipped);

文件读写完整示例
--------------------------------------------------------------------------------

下面示例在 ``Application.attach`` 后把文本写入目标 App ``filesDir`` 子目录，再读回来输出日志。

.. code-block:: javascript

   // ==LSPosedScript==
   // @name         文件读写测试
   // @id           demo.fs.read.write
   // @version      1.0.0
   // @target       *
   // @process      *
   // @run-at       package-loaded
   // @grant        java.full
   // @grant        xposed.full
   // ==/LSPosedScript==

   const TAG = 'FsReadWriteDemo';
   let executed = false;

   xposed.onPackageLoaded(function () {
       const Application = Java.use('android.app.Application');
       const attach = Application.getDeclaredMethod('attach', 'android.content.Context');
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
               xposed.e(TAG, 'fs demo failed', e);
           }

           return result;
       });
   });

多文件 assets 展示示例
--------------------------------------------------------------------------------

.. tip::

   多文件资源复制与展示脚本的完整代码见：
   https://github.com/wojiaoyishang/XiaoHeiCat/tree/master/examples/multi_asset_showcase

示例思路：

1. ``index.js`` 作为多文件脚本入口。
2. ``lib/assets.js`` 读取 ``assets/data/config.json``，并调用 ``syncAssetsToApp``。
3. ``lib/showcase.js`` 在首个 ``Activity.onResume`` 后弹出 Dialog，用 ``ImageView``
   展示复制到私有目录后的图片。

目录结构：

.. code-block:: text

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

核心片段：

.. code-block:: javascript

   const config = JSON.parse(xhh.fs.readAssetText('data/config.json', 'UTF-8'));

   const result = xhh.fs.syncAssetsToApp(context, {
       rootDir: config.targetRootDir,
       overwrite: true,
       clean: false
   });

   const image = xhh.fs.copyAssetToApp(
       context,
       config.imageAsset,
       config.imageTarget,
       {
           rootDir: config.targetRootDir,
           overwrite: true
       }
   );

   xposed.i(TAG, 'targetDir=' + result.targetDir);
   xposed.i(TAG, 'image=' + image.path);

常见错误与排查
--------------------------------------------------------------------------------

``../`` 读取失败
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

现象：``readAssetText('../index.js')`` 或 ``copyAssetToApp(context, '../main.js')`` 抛出路径越界异常。

原因：资源 API 只允许访问当前脚本 ``assets/`` 目录，不能读取脚本源码或其他脚本文件。

解决：把需要给目标 App 使用的文件放进 ``assets/``，例如 ``assets/panel/index.html``。

硬编码 ``/data/user/0`` 导致找不到文件
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

原因：Android 设备可能处于多用户、工作资料夹或其他 profile。真实路径不一定是
``/data/user/0``。

解决：使用 ``xhh.fs.appDirs(context).filesDir`` 或 ``xhh.fs.appAssetDir(context, options)``。

复制到了外部存储但目标 App 读不到
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

原因：目标 App 不一定有外部存储权限，也不一定能访问 XiaoHeiHook 脚本根目录。

解决：使用 ``copyAssetToApp`` 或 ``syncAssetsToApp`` 复制到目标 App ``filesDir``
下的安全子目录，再使用返回值中的 ``path``。

读取大文件导致启动卡顿或 OOM
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

原因：``readText``、``readBytes``、``readAssetText`` 和 ``readAssetBytes`` 都是一次性读取。

解决：默认单文件限制是 ``16MB``。启动阶段只读取小配置、小图片或 HTML/CSS 等资源。
如果确实要读更大的资源，可以显式传 ``{ maxBytes: 更大值 }``，但不要在启动关键路径中处理大文件。

Dialog 或 ImageView 没展示图片
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

排查顺序：

1. 先看 ``copyAssetToApp`` 返回的 ``path`` 是否存在。
2. 使用 ``xhh.fs.exists(path)`` 和 ``xhh.fs.isFile(path)`` 检查文件。
3. 确认 UI 展示发生在主线程和 Activity 生命周期之后。
4. 确认目标文件在当前 App 私有目录内，而不是脚本根目录。
