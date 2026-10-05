# Keep the JS bridge: names are referenced from injected JavaScript by reflection.
-keepclassmembers class com.xlonlabs.iitdcompanion.WebBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.xlonlabs.iitdcompanion.WebBridge { *; }

# WebView + JS interface general safety
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
