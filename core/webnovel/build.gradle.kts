plugins {
    id("epubpro.android.library")
}

android {
    namespace = "com.epubpro.core.webnovel"
}

dependencies {
    implementation(libs.jsoup)
    implementation(libs.javax.inject)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:epub"))
}
