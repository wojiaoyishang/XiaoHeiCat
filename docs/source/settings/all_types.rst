全部设置类型
==================

XiaoHeiHook 动态设置模块支持 12 种设置类型。

类型总览
-----------------

.. list-table::
   :header-rows: 1
   :widths: 20 25 55

   * - 类型
     - 值类型
     - 说明
   * - ``list``
     - 数组
     - 动态列表，支持添加、删除、复制、排序、展开折叠和嵌套子项。
   * - ``group``
     - 无直接值
     - 分组显示多个设置项，内部 radio 会形成单选组布局。
   * - ``heading``
     - 无直接值
     - 标题或分割线。
   * - ``info``
     - 无直接值
     - 信息提示，支持 info / warning / success / error 色调。
   * - ``switch``
     - 布尔值
     - 开关。
   * - ``number``
     - 数字
     - 数字输入与滑块，支持 min / max / step / integer。
   * - ``text``
     - 字符串
     - 文本输入，支持 masked 与 multiline。
   * - ``checkbox``
     - 布尔值
     - 复选框。
   * - ``radio``
     - 单个值
     - 单选项，通常放在 group 中。
   * - ``select``
     - 单个值
     - 下拉选择。
   * - ``custom``
     - 对象
     - 自定义键值对。
   * - ``tags``
     - 字符串数组
     - 标签列表。

完整示例
-----------------

.. code-block:: json

   {
     "version": 1,
     "title": "全部设置类型测试",
     "fields": [
       { "type": "heading", "label": "基础设置" },
       { "type": "info", "tone": "info", "label": "提示", "message": "这是一个设置页示例。" },
       { "key": "enabled", "type": "switch", "label": "启用脚本", "default": true },
       { "key": "debug", "type": "checkbox", "label": "调试模式", "default": false },
       {
         "key": "maxLength",
         "type": "number",
         "label": "最大长度",
         "default": 240,
         "min": 20,
         "max": 2000,
         "step": 10,
         "integer": true
       },
       {
         "key": "tag",
         "type": "text",
         "label": "日志 TAG",
         "default": "XHH",
         "placeholder": "请输入 TAG"
       },
       {
         "key": "mode",
         "type": "select",
         "label": "日志模式",
         "default": "basic",
         "options": [
           { "label": "基础", "value": "basic" },
           { "label": "详细", "value": "verbose" }
         ]
       },
       {
         "type": "group",
         "label": "输出位置",
         "items": [
           { "key": "output", "type": "radio", "label": "终端", "value": "terminal", "default": "terminal" },
           { "key": "output", "type": "radio", "label": "Logcat", "value": "logcat" }
         ]
       },
       {
         "key": "redactHeaders",
         "type": "tags",
         "label": "脱敏 Header",
         "default": ["authorization", "cookie"]
       },
       {
         "key": "replacements",
         "type": "custom",
         "label": "替换规则",
         "default": { "token": "***" }
       },
       {
         "key": "rules",
         "type": "list",
         "label": "规则列表",
         "uniqueKey": "name",
         "items": [
           { "key": "name", "type": "text", "label": "规则名", "default": "rule" },
           { "key": "enabled", "type": "switch", "label": "启用", "default": true }
         ]
       }
     ]
   }
