plugins {
    id("epubpro.android.library")
    id("epubpro.android.hilt")
}

android {
    namespace = "com.epubpro.core.tts"
    ndkVersion = "25.1.8937393"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // sherpa-onnx và onnxruntime-android cùng mang libonnxruntime.so bản 1.27.0 (APK androidTest).
            pickFirsts += "lib/*/libonnxruntime.so"
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sherpa.onnx)
    // VieNeu-TTS v3-Nano: chạy trực tiếp 4 graph ONNX flow-matching (sherpa-onnx chưa hỗ trợ kiến trúc này).
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit4)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
