# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.android.gms.**

# CameraX extensions laddar leverantörsklasser via reflektion
-keep class androidx.camera.extensions.impl.** { *; }
-dontwarn androidx.camera.extensions.impl.**
