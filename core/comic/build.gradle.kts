plugins {
    id("epubpro.android.library")
}

android {
    namespace = "com.epubpro.core.comic"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)

    testImplementation(libs.junit4)
}
