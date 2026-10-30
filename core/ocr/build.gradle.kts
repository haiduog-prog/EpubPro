plugins {
    id("epubpro.android.library")
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

    testImplementation(libs.junit4)
}
