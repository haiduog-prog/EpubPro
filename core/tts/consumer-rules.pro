# Giữ nguyên lớp JNI sea-g2p và ONNX Runtime trong bản release R8
-keep class com.epubpro.core.tts.SeaG2pNative {
    *;
}
-keepclasseswithmembers class com.epubpro.core.tts.SeaG2pEngine {
    native <methods>;
}
-keep class ai.onnxruntime.** { *; }
