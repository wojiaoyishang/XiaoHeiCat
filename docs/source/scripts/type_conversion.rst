JS 与 Java 数据类型转换
================================================================================

本文说明 XiaoHeiHook 脚本在 JS 值与 Java 对象之间传递数据时的转换规则，尤其是
Java Bridge、Hook chain、RPC/MCP 参数和 ``Java.to(...)`` 显式类型构造的边界。

本章描述的 ``Java.to(...)``、反射签名字符串快捷写法，以及“Java 对象传回 Java 时只解包、
不二次自动转换”的规则，从 ``1.32 (109)`` 起提供。

概览
--------------------------------------------------------------------------------

XiaoHeiHook 的 JS Runtime 运行在 Rhino 上。脚本经常需要在 JS 值和 Java 对象之间来回
传递数据。为了兼顾易用性和 Java 反射的准确性，运行时采用两条规则：

* **Java 传给 JS 时，根据来源进行包装。** API 数据结果优先转换为 JS 友好的对象或数组；
  Java Bridge 返回值保留为可继续调用 Java 方法和字段的 wrapper；Hook chain 数据保持既有
  兼容行为。
* **JS 传给 Java 时，根据目标 Java 参数类型转换。** 如果值已经是 Java 对象、Java wrapper
  或 Rhino Java 对象，则只会解包为原始 Java 对象并直接传递，不再二次自动转换；
  如果值是 JS 原生值，才会按目标 Java 参数类型自动转换。

换句话说，``Java.to(...)``、``Java.use(...)`` / ``Java.type(...)``、``loader.loadClass(...)``、
``method.getRawObject()`` 等得到的 Java 值传回 Java 方法时会保留 Java 身份；
不会因为目标参数是 ``Object``、``Number``、``Map`` 或数组就被重新归一化成 JS 值。

当脚本需要精确指定 Java 类型，或者需要传递 ``long``、``BigInteger`` 等可能超过 JS
``number`` 精度的值时，应使用 ``Java.to(...)``。

Java 到 JS 的转换
--------------------------------------------------------------------------------

Java 侧返回值会根据来源使用不同策略，不建议把所有返回值都理解为“普通 JS 对象”。

.. list-table:: Java 到 JS 转换速查
   :header-rows: 1
   :widths: 30 70

   * - 来源或 Java 值
     - JS 侧表现
   * - XiaoHeiHook API 数据返回值
     - 优先转换为 JS 友好的对象或数组，例如 ``xhh.info()``、``xhh.fs.appDirs(context)``、``dex.findMethods(...)``。
   * - ``Map`` / ``JSONObject``
     - JS 普通对象，可使用 ``obj.key`` 或 ``obj['key']``。
   * - ``List`` / ``Iterable`` / Java 数组 / ``JSONArray``
     - JS 数组，可使用 ``arr.length`` 和 ``arr[i]``。
   * - ``Throwable``
     - 调试对象，例如 ``{ type, message, text }``；日志接口仍可识别真正的 Java ``Throwable``。
   * - ``File``
     - 通常转换为文件绝对路径字符串。
   * - ``java.lang.Class``
     - 返回 ``JavaClassWrapper``，可继续读静态字段、调静态方法、执行 ``new``，也可通过 ``classObject`` 或 ``getRawClass()`` 取得原始 ``Class``。
   * - ``Method`` / ``Constructor`` / ``Field`` / ``ClassLoader`` / 普通 Java 对象
     - 返回 Java bridge wrapper，供 Hook 或反射继续使用；传回 Java API 时会自动解包。
   * - Java ``String``、基础包装数字、``Boolean``、``Character``
     - 通常按既有兼容逻辑表现为 JS 可直接使用的字符串、数字、布尔值或对应 wrapper。
   * - ``void``
     - ``undefined``。
   * - ``null``
     - ``null``。

JS 到 Java 的自动转换
--------------------------------------------------------------------------------

JS 调用 Java 方法、构造函数、字段写入、``chain.proceed(args)`` 或 Java proxy 回调返回值时，
运行时会根据目标 Java 类型转换参数。

.. list-table:: JS 到 Java 自动转换速查
   :header-rows: 1
   :widths: 30 70

   * - JS 侧值
     - 目标 Java 类型与转换结果
   * - Java wrapper / Rhino Java 对象
     - 直接解包为原始 Java 对象，不再二次转换；例如 ``Java.to("java.lang.Long", "1")`` 得到的值传给 Java 时仍是 ``Long``。
   * - JS ``string``
     - 可转为 ``String`` / ``CharSequence``；目标是数字或布尔类型时，部分场景可按 Java parse 语义处理。
   * - JS ``number``
     - 目标是 ``int`` / ``long`` / ``float`` / ``double`` / ``short`` / ``byte`` 时，按 Java 数字语义转换。
   * - JS ``boolean``
     - 目标是 ``boolean`` / ``Boolean`` 时直接转换。
   * - JS ``function``
     - 目标是 Java 单抽象方法接口（SAM）时，可自动代理。
   * - JS 数组
     - 目标是 Java 数组或 varargs 时，逐项按元素类型转换。
   * - JS 对象字面量
     - API 数据场景通常按 JS 对象使用；要精确传给 ``Map`` 或自定义 Java 类型时建议使用 ``Java.to(...)``。
   * - 目标 Java 参数是 ``Object``
     - 缺少足够类型信息，JS ``number`` 可能按 Rhino 默认规则成为 ``Double``。需要 ``Integer``、``Long``、``BigInteger`` 等精确类型时应使用 ``Java.to(...)``。

``Java.to`` 显式构造 Java 类型
--------------------------------------------------------------------------------

.. function:: Java.to(type, value[, options])
   :no-index:

   从 ``1.32 (109)`` 起提供。将 JS 值显式转换为指定 Java 类型。

   :param type: Java 类型。可以是类名字符串、基础类型名称、数组类型名称、``JavaClassWrapper`` 或 ``java.lang.Class``。
   :param value: 要转换的 JS 值。
   :param options: 可选转换参数。
   :return: Java 值或 Java wrapper，可直接传给 Java 方法、构造函数、``Method.invoke`` 或 ``chain.proceed``。

常见用途：

* 构造 Java ``Integer``、``Long``、``Boolean`` 等包装类型。
* 避免超长整数在 JS ``number`` 中丢失精度。
* 构造 ``BigInteger``、``BigDecimal``。
* 构造 Java ``byte[]``、``String[]``、``int[]`` 等数组。
* 构造 ``ArrayList``、``HashMap`` 等集合。
* 在目标 Java 参数是 ``Object`` 时显式指定真实类型。

``type`` 参数支持以下形式：

.. code-block:: javascript

   Java.to("int", 1);
   Java.to("long", "1234567890123456789");
   Java.to("java.math.BigInteger", "123456789012345678901234567890");
   Java.to("byte[]", [1, 2, 3]);
   Java.to("java.lang.String[]", ["a", "b"]);

   const Integer = Java.use("java.lang.Integer");
   Java.to(Integer, 1);
   Java.to(Integer.classObject, 1);

常用类型速查表
--------------------------------------------------------------------------------

.. list-table:: ``Java.to`` 常用类型
   :header-rows: 1
   :widths: 28 42 30

   * - 目标类型
     - 示例
     - 说明
   * - ``int`` / ``java.lang.Integer``
     - ``Java.to("int", 1)``
     - ``null`` 不允许转 primitive；字符串按 ``Integer.parseInt`` 语义处理。
   * - ``long`` / ``java.lang.Long``
     - ``Java.to("long", "1234567890123456789")``
     - 推荐使用字符串，避免 JS ``number`` 精度丢失。
   * - ``boolean`` / ``java.lang.Boolean``
     - ``Java.to("boolean", true)``
     - 字符串 ``"true"`` / ``"false"`` 可转换。
   * - ``char`` / ``java.lang.Character``
     - ``Java.to("char", "A")``
     - 多字符字符串默认报错；可用 ``{ firstChar: true }`` 取首字符。
   * - ``float`` / ``double``
     - ``Java.to("double", "3.14")``
     - 可由 JS ``number`` 或字符串构造。
   * - ``java.lang.String``
     - ``Java.to("java.lang.String", value)``
     - ``null`` 默认仍为 ``null``；可用 ``{ nullAsEmpty: true }``。
   * - ``java.math.BigInteger``
     - ``Java.to("java.math.BigInteger", "98765432101234567890")``
     - 强烈建议使用字符串。
   * - ``java.math.BigDecimal``
     - ``Java.to("java.math.BigDecimal", "3.1415926535897932384626")``
     - 强烈建议使用字符串。
   * - ``byte[]``
     - ``Java.to("byte[]", "SGVsbG8=", { encoding: "base64" })``
     - 支持 JS number array、Base64 字符串、UTF-8 字符串。
   * - ``int[]`` / ``long[]`` / ``double[]``
     - ``Java.to("long[]", ["1234567890123456789", "2"])``
     - 每个元素按数组元素类型转换。
   * - ``java.lang.String[]`` / ``java.lang.Object[]``
     - ``Java.to("java.lang.String[]", ["a", "b"])``
     - ``Object[]`` 如需精确元素类型，可设置 ``elementType``。
   * - ``java.util.ArrayList``
     - ``Java.to("java.util.ArrayList", [1, 2], { elementType: "java.lang.Integer" })``
     - 未设置 ``elementType`` 时按普通 JS 值转换。
   * - ``java.util.HashSet``
     - ``Java.to("java.util.HashSet", ["a", "b"])``
     - 可设置 ``elementType``。
   * - ``java.util.HashMap``
     - ``Java.to("java.util.HashMap", { a: 1 }, { valueType: "java.lang.Integer" })``
     - 可设置 ``keyType`` / ``valueType``；key 默认建议按 ``String`` 理解。
   * - enum
     - ``Java.to("com.example.Mode", "DEBUG")``
     - 字符串会按 ``Enum.valueOf`` 转换。
   * - ``java.lang.Class``
     - ``Java.to("java.lang.Class", "java.lang.String")``
     - 返回对应 raw ``Class``。
   * - ``java.io.File``
     - ``Java.to("java.io.File", "/data/user/0/pkg/files/a.txt")``
     - 等价于 ``new File(path)``。
   * - ``android.net.Uri``
     - ``Java.to("android.net.Uri", "file:///data/a.png")``
     - Android 环境可用时调用 ``Uri.parse``。

``options`` 参数
--------------------------------------------------------------------------------

``Java.to`` 的第三个参数用于控制集合元素、Map key/value、字节编码和少量严格转换策略。

.. list-table:: 常用 ``options``
   :header-rows: 1
   :widths: 28 72

   * - 选项
     - 说明
   * - ``elementType``
     - 数组、``List``、``Set`` 或 ``Object[]`` 的元素类型，例如 ``"java.lang.Integer"``。
   * - ``keyType``
     - ``Map`` key 类型，常见为 ``"java.lang.String"``。
   * - ``valueType``
     - ``Map`` value 类型，例如 ``"java.lang.Long"``。
   * - ``encoding``
     - 字符串转 ``byte[]`` 时的编码，例如 ``"base64"`` 或 ``"utf-8"``。
   * - ``unsigned``
     - ``byte`` / ``byte[]`` 是否允许 ``0`` 到 ``255`` 的无符号输入。
   * - ``deep``
     - 嵌套 JS array/object 是否递归转为 ``ArrayList`` / ``HashMap``，默认建议按 ``true`` 理解。
   * - ``nullAsEmpty``
     - ``String`` 目标类型下是否把 ``null`` 转为空字符串。
   * - ``firstChar``
     - ``char`` 目标类型下是否允许多字符字符串取首字符。
   * - ``numberAsBoolean``
     - 是否允许数字转布尔。
   * - ``booleanAsNumber``
     - 是否允许布尔转数字。
   * - ``defaultValue``
     - ``value`` 为 ``null`` / ``undefined`` 时使用的默认值。

超长整数与精度问题
--------------------------------------------------------------------------------

JS ``number`` 是 IEEE-754 double，安全整数范围有限。订单号、雪花 ID、时间戳、加密参数、
``long``、``BigInteger``、``BigDecimal`` 等值只要可能超过安全整数范围，就不要直接写成 JS
数字字面量。

错误示例：

.. code-block:: javascript

   obj.setId(1234567890123456789);   // 这个值在 JS number 中已经不能精确表示

正确示例：

.. code-block:: javascript

   obj.setId(Java.to("long", "1234567890123456789"));

   const bigId = Java.to(
       "java.math.BigInteger",
       "123456789012345678901234567890"
   );
   obj.setBigId(bigId);

``Method.invoke`` 的参数转换
--------------------------------------------------------------------------------

``Method.invoke`` 表面签名是 ``Method.invoke(Object receiver, Object... args)``，但真正需要转换的是
该 ``Method`` 代表的目标方法参数。运行时会尽量按真实目标方法签名转换 ``args``。

当参数复杂、目标参数是 ``Object``，或者需要避免精度问题时，仍建议显式使用 ``Java.to``：

.. code-block:: javascript

   const method = TargetClass.getDeclaredMethod(
       "decrypt",
       "java.lang.String",
       "java.lang.String",
       "java.lang.String",
       "int"
   );

   method.setAccessible(true);

   const result = method.invoke(
       null,
       Java.to("java.lang.String", params.data),
       Java.to("java.lang.String", params.key),
       Java.to("java.lang.String", params.iv),
       Java.to("int", params.mode || 0)
   );


从 ``1.32 (109)`` 起，反射签名里的 ``Class`` 参数也支持快捷写法。调用 ``getDeclaredMethod``、``getMethod``、
``getDeclaredConstructor``、``getConstructor`` 等 ``java.lang.Class`` 方法时，可以直接把
类名字符串或基础类型名称传给签名参数：

.. code-block:: javascript

   const method = TargetClass.getDeclaredMethod(
       "decrypt",
       "java.lang.String",
       "java.lang.String",
       "java.lang.String",
       "int"
   );

这等价于传入 ``String.class``、``String.class``、``String.class``、``int.class``，无需再写
``Java.use("java.lang.Integer").TYPE`` 或 ``Java.type("java.lang.Integer").TYPE``。如果参数类型来自特殊 ``ClassLoader``，仍可以传入
``loader.loadClass(name)`` 得到的原始 ``Class``。

.. tip::

   完整反射能力检查脚本见仓库 ``examples/java_reflection_smoke_test.js``；综合 Bridge
   检查脚本见 ``examples/java_bridge_smoke_test.js``。

``chain.proceed`` 与 Hook return 的转换
--------------------------------------------------------------------------------

修改 Hook 参数后继续执行时，``chain.proceed(args)`` 会按当前 Hook 目标方法的参数类型转换：

.. code-block:: javascript

   xposed.hook(method).intercept(function (chain) {
       return chain.proceed([
           Java.to("java.lang.String", "data"),
           Java.to("java.lang.String", "key"),
           Java.to("int", 0)
       ]);
   });

Hook 回调返回值最终会作为目标 Java 方法的返回值，应按目标方法 ``returnType`` 理解：

.. code-block:: javascript

   xposed.hook(method).intercept(function (chain) {
       return Java.to("int", 100);
   });

如果目标返回 ``void``，返回 ``undefined`` 或 ``null`` 没有实际意义。

RPC/MCP params 的转换
--------------------------------------------------------------------------------

``register_method`` 的 ``params`` 是 JSON 字典入参。脚本侧既可以使用点访问，也可以使用
``get`` 访问：

.. code-block:: javascript

   xhh.rpc.register_method("decrypt", {}, function (params) {
       const data = params.data;
       const key = params.get("key");
       const mode = Java.to("int", params.mode || 0);
       return doDecrypt(data, key, mode);
   });

如果内部实现使用 Java ``Map``，也应保持 wrapper 的点访问能力，不应强迫脚本只能写
``params.get("data")``。

多进程与 Java 对象边界
--------------------------------------------------------------------------------

``Java.to`` 构造的是当前目标进程内可用的 Java 值或 Java wrapper。它不能让 Java 对象跨 Android
进程传递，也不解决 native 库未加载导致的 ``UnsatisfiedLinkError``。

需要注意：

* ``xhh.global`` 只在同一目标进程内共享，不跨进程持久化。
* ``Method``、``ClassLoader``、``thisObject``、``View``、``Activity`` 等对象只能在当前进程和对象生命周期内使用。
* 需要跨进程或跨重启保存的数据，应转换为字符串、数字、JSON 或文件内容等可序列化形式。
