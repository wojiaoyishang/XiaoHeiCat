# demo.multi.asset.showcase

这是一个 XiaoHeiHook 1.30 (107) `xhh.fs` 多文件脚本示例。

## 放置位置

把整个目录放到当前用户脚本根目录下：

```text
/storage/emulated/<当前用户>/Documents/XiaoHeiHook/demo.multi.asset.showcase/
```

目录脚本入口是：

```text
demo.multi.asset.showcase/index.js
```

## 功能

应用启动时：

1. `Application.attach(Context)` 中读取脚本 `assets/data/config.json`
2. 调用 `xhh.fs.syncAssetsToApp(context, options)` 同步所有 assets
3. 调用 `xhh.fs.copyAssetToApp(...)` 获取图片和 HTML 的完整目标路径
4. 目标路径位于目标 App 私有目录：

```text
<context.getFilesDir()>/xhh_showcase/demo.multi.asset.showcase/
```

5. 首个 `Activity.onResume()` 时尝试弹出 Dialog，并用 `ImageView` 展示复制后的图片。

## 主要文件

```text
demo.multi.asset.showcase/
  index.js
  main.js
  lib/
    assets.js
    showcase.js
  assets/
    data/config.json
    images/generated-showcase.png
    panel/index.html
    panel/style.css
```

## 修改目标目录

修改：

```text
assets/data/config.json
```

中的：

```json
{
  "targetRootDir": "xhh_showcase/demo.multi.asset.showcase"
}
```

该路径是相对于目标 App `filesDir` 的安全子路径，不能使用绝对路径，也不能包含 `../`。
