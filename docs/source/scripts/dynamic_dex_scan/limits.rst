限制
====

* v47 的稳定主路径依赖 ART ``DexFile cookie``，需要目标类能通过真实 app ClassLoader 加载。
* 若壳把方法体进一步抽取到 native 或运行时修复 CodeItem，导出的 dex 可能只有类/方法定义，没有完整方法体。
* ``dex.dump`` 是显式授权；未声明 grant 时不注入 ``dex``。
* 不建议在启动回调中批量打开大量 dex。
