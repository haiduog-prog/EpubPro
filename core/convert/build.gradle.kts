plugins {
    id("epubpro.kotlin.jvm.library")
}

dependencies {
    implementation(libs.jsoup)

    testImplementation(libs.junit4)
}
