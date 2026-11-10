plugins {
    id("epubpro.android.library")
    id("epubpro.android.hilt")
}

android {
    namespace = "com.epubpro.core.tts"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        jniLibs {
            // sherpa-onnx và onnxruntime-android cùng mang libonnxruntime.so bản 1.27.0 (APK androidTest).
            pickFirsts += "lib/*/libonnxruntime.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sherpa.onnx)
    // PoC VieNeu-TTS v3 Nano: gọi trực tiếp các graph ONNX (sherpa-onnx không hỗ trợ kiến trúc flow-matching này).
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
