方法定位与检查
==============

v47 不再推荐先做泛搜索。推荐先通过类名/方法名/原型定位目标方法所在 dex，再检查方法体特征。

目标方法参数
------------

.. code-block:: javascript

   {
     className: 'pc.a',
     methodName: 'd',
     proto: '()Z'
   }

方法体特征
----------

.. code-block:: javascript

   {
     strings: ['am7_dev_vip_override', 'getString(...)', 'vip', 'nonvip'],
     invokeContains: ['->getString(']
   }

返回结果中的 ``featuresOk`` 为 ``true`` 时，说明字符串和调用特征都命中。
