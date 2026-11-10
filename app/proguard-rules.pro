# ProGuard / R8 rules for EpubPro

# Keep Kotlin annotations & reflection components
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Jetpack Compose rules
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}

# Kotlin Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }

# Hilt / Dagger
-keep class * extends dagger.hilt.internal.UnsafeCasts { *; }
-keepclassmembers class * {
    @dagger.hilt.** *;
}

# Android Architecture Components / ViewModel
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Room Database (if used)
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# EPUB / Reader models & Data classes (Prevent obfuscation of serialized fields)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep data classes in domain and core reader models if reflection/serialization is used
-keep class com.epubpro.domain.model.** { *; }
-keep class com.epubpro.core.reader.model.** { *; }

# Sherpa ONNX TTS
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ONNX Runtime Java API (VietOCR): JNI tạo và gọi ngược các lớp này.
-keep class ai.onnxruntime.** { *; }

