阶段选择
===============

建议优先使用单一路径：真实 ``appClassLoader`` + ``DexFile cookie dump``。

推荐流程
--------

1. 在 ``Application.attach(Context)`` 后拿 ``context.getClassLoader()``。
2. 用该 loader 加载业务类，例如 ``loader.loadClass('pc.a')``。
3. 调用 ``dex.dumpAndInspectMethod`` 或 ``dex.dumpDecryptedDexForMethod``。
4. 如果 ``featuresOk=true``，说明导出的 dex 和方法体都正确。

不再推荐的默认流程
------------------

以下能力保留用于诊断，但不再作为默认脱壳主路径：

* 全量 ``/proc/self/maps`` memory scan。
* raw salvage dump。
* 多目录 fallback。
* 一次打开几十个 cookie dex 后全量搜索。

原因是这些流程容易得到壳 dex、重复 dex，或触发 OOM。

Grant 控制
----------

没有 ``@grant dex.dump`` / ``dumpdex`` / ``dex.full`` 时：

* 不注入全局 ``dex``。
* 不安装 early Dex runtime capture。
* 不安装 class-load dumper。

这样普通 Hook 脚本不会承担 dumpdex 的启动成本。
