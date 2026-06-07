settings.json 结构
==================

基本结构
-----------------

``settings.json`` 只负责声明 schema，不保存用户值。

.. code-block:: json

   {
     "version": 1,
     "title": "OkHttp 日志设置",
     "fields": [
       {
         "key": "enabled",
         "type": "switch",
         "label": "启用日志",
         "default": true
       },
       {
         "key": "tag",
         "type": "text",
         "label": "日志 TAG",
         "default": "XHH-OkHttp"
       }
     ]
   }

字段安全规则
-----------------

配置项 key 必须满足：

.. code-block:: text

   ^[A-Za-z_][A-Za-z0-9_]{0,63}$

禁止使用：

.. code-block:: text

   __proto__
   prototype
   constructor

类型别名
-----------------

为了兼容常见 JSON schema 写法，以下类型会被归一化：

.. list-table::
   :header-rows: 1
   :widths: 30 30 40

   * - 输入类型
     - 归一化类型
     - 说明
   * - ``boolean``
     - ``switch``
     - 布尔开关。
   * - ``string``
     - ``text``
     - 文本。
   * - ``integer``
     - ``number``
     - 整数数字。
   * - ``stringArray`` / ``array``
     - ``tags``
     - 字符串标签数组。

保存校验
-----------------

保存用户配置时会按 schema 过滤：

- schema 中没有声明的 key 会被丢弃。
- 类型不匹配会保存失败。
- ``number`` 会按 ``min`` / ``max`` 限制。
- ``text`` 会按 ``maxLength`` 限制。
- ``tags`` 和 ``list`` 会按 ``maxItems`` 限制。
- ``list`` 可以通过 ``uniqueKey`` 去重。
