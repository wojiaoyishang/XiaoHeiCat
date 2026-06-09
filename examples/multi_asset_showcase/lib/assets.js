function readConfig() {
  const text = xhh.fs.readAssetText("data/config.json", "UTF-8");
  return JSON.parse(text);
}

function prepare(context, tag) {
  const config = readConfig();

  const options = {
    rootDir: config.targetRootDir,
    overwrite: true,
    clean: false,
    maxBytes: config.maxBytes || (16 * 1024 * 1024)
  };

  const sync = xhh.fs.syncAssetsToApp(context, options);
  const image = xhh.fs.copyAssetToApp(
    context,
    config.imageAsset,
    config.imageTarget,
    options
  );
  const html = xhh.fs.copyAssetToApp(
    context,
    config.htmlAsset,
    config.htmlTarget,
    options
  );

  xposed.i(
    tag,
    "sync copied=" + sync.copied +
      " skipped=" + sync.skipped +
      " overwritten=" + sync.overwritten +
      " deleted=" + sync.deleted
  );

  return {
    config: config,
    options: options,
    sync: sync,
    image: image,
    html: html,
    targetDir: xhh.fs.appAssetDir(context, options)
  };
}

module.exports = {
  readConfig: readConfig,
  prepare: prepare
};
