# Names are kept: R8 shrinks and optimises, and a crash report stays readable.
-dontobfuscate

# The hub talks to the app through this object, by name, from JavaScript: every method the page can call must survive.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class app.couch.alhasapa.bridge.HubBridge { *; }

# Read by name from the manifest / WebView internals.
-keep class app.couch.alhasapa.tv.CouchWebView { *; }
