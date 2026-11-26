plugins {
    id("epubpro.android.library")
    id("epubpro.android.hilt")
}

android {
    namespace = "com.epubpro.core.database"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // MigrationTestHelper đọc file schema JSON từ assets của androidTest.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

// Xuất schema Room ra thư mục được commit để kiểm tra migration bằng MigrationTestHelper.
kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:common"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.mockito.core)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
