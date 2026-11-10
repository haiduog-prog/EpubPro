plugins {
    id("epubpro.android.library")
    id("epubpro.android.hilt")
}

android {
    namespace = "com.epubpro.core.ocr"
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)

    // ML Kit bản qua Google Play services: model OCR do Play services tải, không làm phình APK.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.mlkit.text.recognition.korean)
    implementation(libs.mlkit.translate)

    // VietOCR (ONNX, ~39 MiB trong assets/vietocr) nhận dạng lại từng dòng ML Kit tìm được để giữ đúng dấu tiếng Việt.
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit4)
}
