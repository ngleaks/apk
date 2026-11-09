# Keep Media3 / ExoPlayer reflection points (renderers, extractors, HLS).
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# WebView JS bridge is called by name from JavaScript, never directly from Java.
-keepclassmembers class com.pbb.streamtv.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.pbb.streamtv.Bridge { *; }

# AppCompat keeps a few View constructors alive via XML inflation.
-keep public class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
}
