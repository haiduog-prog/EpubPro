plugins {
    id("epubpro.android.library")
    id("epubpro.android.compose")
    id("epubpro.android.hilt")
}

android {
    namespace = "com.epubpro.core.security"
}

dependencies {
    implementation(project(":core:designsystem"))

    // api: module gọi (app, feature) cần thấy FragmentActivity mà BiometricPrompt yêu cầu.
    api(libs.androidx.biometric)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit4)
}
