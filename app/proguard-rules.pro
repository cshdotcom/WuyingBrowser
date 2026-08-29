# Keep Application class
-keep class com.wuying.browser.BrowserApplication { *; }
# Keep WebView JS interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# Keep Parcelize
-keep @kotlinx.parcelize.Parcelize class *
-keep class kotlin.Parcelize
