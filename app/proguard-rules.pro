# Add project specific ProGuard rules here.
-keep class com.pickcode.app.data.** { *; }
-keep class com.pickcode.app.ocr.** { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
}
