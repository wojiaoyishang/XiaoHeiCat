let hookInstalled = false;
let shown = false;

function installActivityDialog(prepared, tag) {
  if (hookInstalled) return;
  hookInstalled = true;

  const Activity = Java.type("android.app.Activity");
  const onResume = Activity.getDeclaredMethod("onResume");
  onResume.setAccessible(true);

  xposed.hook(onResume).intercept(function (chain) {
    const result = chain.proceed();

    if (!shown) {
      shown = true;
      const activity = chain.getThisObject();
      postShow(activity, prepared, tag);
    }

    return result;
  });
}

function postShow(activity, prepared, tag) {
  const Handler = Java.type("android.os.Handler");
  const Looper = Java.type("android.os.Looper");

  const handler = new Handler(Looper.getMainLooper());
  const delay = prepared.config.showDelayMs || 800;

  handler.postDelayed(Java.proxy("java.lang.Runnable", {
    run: function () {
      try {
        showDialog(activity, prepared);
        xposed.i(tag, "dialog shown, image=" + prepared.image.path);
      } catch (e) {
        xposed.e(tag, "show dialog failed, image=" + prepared.image.path, e);
      }
    }
  }), delay);
}

function showDialog(activity, prepared) {
  const AlertDialogBuilder = Java.type("android.app.AlertDialog$Builder");
  const LinearLayout = Java.type("android.widget.LinearLayout");
  const ImageView = Java.type("android.widget.ImageView");
  const TextView = Java.type("android.widget.TextView");
  const Uri = Java.type("android.net.Uri");
  const File = Java.type("java.io.File");
  const ViewGroupLayoutParams = Java.type("android.view.ViewGroup$LayoutParams");
  const LinearLayoutLayoutParams = Java.type("android.widget.LinearLayout$LayoutParams");
  const ImageScaleType = Java.type("android.widget.ImageView$ScaleType");

  const padding = dp(activity, 20);

  const layout = new LinearLayout(activity);
  layout.setOrientation(LinearLayout.VERTICAL);
  layout.setPadding(padding, padding, padding, padding);

  const image = new ImageView(activity);
  image.setAdjustViewBounds(true);
  image.setScaleType(ImageScaleType.CENTER_INSIDE);
  image.setImageURI(Uri.fromFile(new File(prepared.image.path)));

  const imageParams = new LinearLayoutLayoutParams(
    ViewGroupLayoutParams.MATCH_PARENT,
    dp(activity, 220)
  );
  layout.addView(image, imageParams);

  const message = new TextView(activity);
  message.setText(prepared.config.message + "\n\n" + prepared.image.path);
  message.setTextSize(13);
  layout.addView(message);

  const builder = new AlertDialogBuilder(activity);
  builder.setTitle(prepared.config.dialogTitle || "XHH Asset 展示");
  builder.setView(layout);
  builder.setPositiveButton("OK", null);

  const dialog = builder.create();
  dialog.show();
}

function dp(context, value) {
  return Math.round(value * context.getResources().getDisplayMetrics().density);
}

module.exports = {
  installActivityDialog: installActivityDialog
};
