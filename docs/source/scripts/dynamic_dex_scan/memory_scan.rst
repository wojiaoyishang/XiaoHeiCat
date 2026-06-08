内存 Dex 扫描
=============

内存 Dex 扫描用于处理真实 dex 没有落地文件、Java 层捕获不到 loader 来源的情况。

它依赖 native so，在当前进程中读取 ``/proc/self/maps``，扫描可读内存区域，搜索 dex magic 并 dump。

API
---

``dex.scanMemory(options)``
---------------------------

扫描当前进程内存并 dump 可疑 dex。

示例：

.. code-block:: javascript

   var result = dex.scanMemory({
     maxRegionBytes: 256 * 1024 * 1024,
     maxDumpBytes: 256 * 1024 * 1024,
     maxDumpCount: 24,
     includeAnonymous: true,
     includeFileBacked: true,
     requireAsciiContains: [
       "am7_dev_vip_override",
       "work/am7code"
     ]
   });

   console.log(JSON.stringify(result));

常用参数：

``maxRegionBytes``
    单个内存区域最大扫描大小。建议 128MB 到 512MB。

``maxDumpBytes``
    单个 dex dump 最大允许大小。内存 dex 可能超过 50MB，建议至少 256MB。

``maxDumpCount``
    最多 dump 多少个 dex，避免日志和磁盘爆炸。

``includeAnonymous``
    是否扫描匿名映射，例如 ``[anon:*]``、``/dev/ashmem``、``memfd``。

``includeFileBacked``
    是否扫描文件映射。

``requireAsciiContains``
    可选过滤条件。dump 前检查候选 dex 内是否包含指定 ASCII 字符串之一。用于减少误报。

返回值示例：

.. code-block:: json

   {
     "available": true,
     "count": 2,
     "sources": [
       {
         "path": "/data/user/0/pkg/code_cache/xhh_memory_dex/memdex_001.dex",
         "size": 74362708,
         "type": "memory",
         "region": "anon"
       }
     ]
   }

``dex.memorySources()``
-----------------------

返回内存扫描已 dump 的 dex 来源。

示例：

.. code-block:: javascript

   var sources = dex.memorySources();
   console.log("memory source count=" + sources.length);
   console.log(JSON.stringify(sources));

``dex.fromMemory()``
--------------------

把已 dump 的内存 dex 打开为 Dex 分析对象。

示例：

.. code-block:: javascript

   var df = dex.fromMemory();
   console.log("memory classes=" + df.classes().length);

加载 so
-------

native 扫描依赖模块 APK 中存在：

.. code-block:: text

   lib/arm64-v8a/libxhh_dexscan.so

如果日志出现：

.. code-block:: text

   couldn't find "libxhh_dexscan.so"

说明 native so 没有被打进 APK，或 ABI 不匹配。

建议检查：

* ``build.gradle.kts`` 是否启用 ``externalNativeBuild``。
* APK 内是否有 ``lib/<abi>/libxhh_dexscan.so``。
* 目标设备 ABI 是否与 so 匹配。
* LSPosed 模块加载器的 nativeLibraryDirectories 是否包含模块 APK 的 lib 目录。

大文件限制
----------

内存 dex dump 可能很大，例如 74MB、100MB 或更大。

如果日志出现：

.. code-block:: text

   dex 文件过大，拒绝读取: ... size=74362708

说明 Java 层读取限制太低。建议：

* 普通 dex 默认上限：128MB。
* memory dex 默认上限：256MB 或 512MB。
* 暴露 ``dex.setLimits`` 或 ``dex.fromMemory({ maxDexBytes: ... })``。

示例：

.. code-block:: javascript

   dex.setLimits({
     maxDexBytes: 256 * 1024 * 1024,
     maxSmaliChars: 500000
   });

   var df = dex.fromMemory();

误报处理
--------

内存扫描只凭 magic 不可靠。native 层应至少校验：

* magic 为 ``dex\n035``、``dex\n037``、``dex\n038``、``dex\n039`` 或 ``dex\n040``。
* ``header_size == 0x70``。
* ``endian_tag == 0x12345678``。
* ``file_size`` 合理。
* ``string_ids_off``、``type_ids_off``、``method_ids_off``、``class_defs_off`` 不越界。
* ``class_defs_size > 0``。
* ``map_off`` 和 ``map_size`` 合理。

如果 dexlib2 解析失败，应在日志中输出 header 详情，帮助判断是误报、截断、vdex/oat 映射，还是壳抹掉了 header。
