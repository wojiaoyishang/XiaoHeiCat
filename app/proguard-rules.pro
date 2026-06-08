# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# Rhino RegExp support is installed explicitly from JsHookRuntime.
# Keep the implementation in release builds so JavaScript /.../ literals,
# RegExp, String.match/replace/search/split keep working after minification.
-keep class org.mozilla.javascript.regexp.** { *; }
-keep class org.mozilla.javascript.RegExpProxy { *; }
-keepclassmembers class org.mozilla.javascript.ScriptRuntime { *; }
