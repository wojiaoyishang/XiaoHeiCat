动态 Dex 示例脚本
================================================================================

本目录放置与动态 Dex 相关的推荐脚本。示例均使用 ``ret.paths``、``xhh.each``、
JS 对象参数和 JS 数组返回值，不再使用 Java ``List`` / ``Map`` 兼容写法。

.. list-table:: 示例文件
   :header-rows: 1
   :widths: 38 62

   * - 文件
     - 说明
   * - ``examples/dumpdex_smali_search_xhh_each.js``
     - Application.attach 后 dump dex，再按 smali 特征搜索；找到第一个目标后停止。
   * - ``examples/js_api_stable_smoke_test.js``
     - JS API 返回值稳定化 smoke test。
