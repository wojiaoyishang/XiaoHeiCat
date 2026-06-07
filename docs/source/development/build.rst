编译说明
==================

XiaoHeiHook 采用标准 Android 项目结构开发，使用 Gradle 进行构建，并依赖现代 LSPosed API、Rhino JavaScript 引擎以及 Jetpack Compose 等组件。

环境要求
------------------

在开始编译之前，请确保开发环境满足以下要求：

- Android Studio Meerkat 或更高版本
- JDK 21
- Android SDK 35
- Gradle 9.x
- Android Gradle Plugin 8.x

获取源码
------------------

从 GitHub 克隆项目源码：

.. code-block:: bash

   git clone https://github.com/wojiaoyishang/XiaoHeiCat.git

或者直接下载源码压缩包后解压。

构建 WebIDE
------------------

WebIDE 前端采用 React + TypeScript + Vite 开发。

进入前端目录：

.. code-block:: bash

   cd webide-src

安装依赖：

.. code-block:: bash

   npm install

构建前端资源：

.. code-block:: bash

   npm run build

构建完成后会自动输出到：

.. code-block:: text

   app/src/main/assets/webide/

Android 端会直接加载这些静态资源，无需额外部署 Web 服务。

编译项目
------------------

进入项目根目录后执行：

.. code-block:: bash

   ./gradlew assembleDebug

生成的 APK 位于：

.. code-block:: text

   app/build/outputs/apk/debug/

如果需要生成 Release 版本：

.. code-block:: bash

   ./gradlew assembleRelease

构建文档
------------------

项目文档基于 Sphinx 构建。

安装依赖：

.. code-block:: bash

   pip install -r docs/requirements.txt

生成 HTML 文档：

.. code-block:: bash

   cd docs
   sphinx-build ./source ./_build

生成完成后可直接打开：

.. code-block:: text

   docs/_build/index.html

调试说明
------------------

建议在开发过程中使用 Debug 构建：

.. code-block:: bash

   ./gradlew installDebug

安装完成后：

1. 在 LSPosed 中启用模块。
2. 将模块作用域勾选到目标应用。
3. 重启目标应用。
4. 打开 XiaoHeiHook 管理端验证状态。

.. note::

   如果修改了 WebIDE 前端代码，需要重新执行 ``npm run build`` 后再重新编译 Android 应用。

.. warning::

   Android Studio 首次同步项目时可能需要下载对应版本的 Gradle、Android SDK 和 NDK，请确保网络环境正常。