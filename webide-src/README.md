# XiaoHeiHook WebIDE source

这是正式 WebIDE 的 React + Monaco 源码目录。

## 开发

```bash
cd webide-src
npm install
npm run dev
```

开发服务器默认访问真实 Android 后端时，需要自己配置代理或直接通过 `adb forward` 访问手机端服务。

## 构建到 Android assets

```bash
cd webide-src
npm install
npm run build
```

构建产物会输出到：

```text
../app/src/main/assets/webide
```

Android 端只托管构建后的静态文件，不在手机上运行 Node/Vite。

## 说明

- 编辑器直接使用 `monaco-editor`。
- 通过 Vite worker import 处理 Monaco worker。
- UI 保持传统三栏 IDE 风格，不使用复杂现代组件库。


## v12.2 build fix

如果从 v12.1 升级，请重新执行：

```bash
npm install
npm run build
```

本版已补充 Vite 类型声明、React DOM 类型声明、Monaco Worker 类型声明和 TypeScript 7 兼容配置。
