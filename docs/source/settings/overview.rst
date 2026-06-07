配置项设计
==================

.. tip::
	具体包含设置项的脚本可以参考仓库目录 `example/settings_all_types` 下的脚本，其中包含了所有支持的设置项的演示。

配置项工作流
-----------------

1. 脚本目录声明 settings.json （ **单脚本不支持设置项配置** ）
2. XiaoHeiHook 扫描 settings.json 得到配置表单
3. 用户在某个应用详情页中修改配置
4. 按 packageName + scriptId 单独保存配置值
5. 目标 App 进程运行脚本时读取自己的配置

保存位置
-----------------

用户配置不会写回 ``settings.json``，而是保存到 LSPosed Remote Preferences。

保存 key：

.. code-block:: text

   script_settings_<packageName>_<scriptId>

保存文档结构：

.. code-block:: json

   {
     "version": 1,
     "packageName": "cn.am7code.tools",
     "scriptId": "demo.okhttp.logger",
     "scriptPath": "okhttp_logger/index.js",
     "updatedAt": 1780000000000,
     "values": {
       "enabled": true,
       "tag": "XHH-OkHttp"
     }
   }

默认值合并
-----------------

运行脚本时会按以下顺序合并配置：

1. settings.json default values
2. 用户保存 values 覆盖 default
3. 脚本读取最终 merged settings

脚本读取示例：

.. code-block:: javascript

   const enabled = settings.get('enabled', true);
   const tag = settings.get('tag', 'XHH');
