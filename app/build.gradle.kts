import java.io.FileInputStream
import java.util.Properties

plugins {
    id("epubpro.android.application")
    id("epubpro.android.compose")
    id("epubpro.android.hilt")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.epubpro.app"

    defaultConfig {
        applicationId = "com.epubpro.app"
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    signingConfigs {
        val sharedKeystore = rootProject.file("keystore/tester.jks")

        getByName("debug") {
            if (sharedKeystore.exists()) {
                storeFile = sharedKeystore
                storePassword = "epubpro_shared_key"
                keyAlias = "epubpro"
                keyPassword = "epubpro_shared_key"
            }
            enableV1Signing = true
            enableV2Signing = true
        }

        create("release") {
            val storeFileProp = keystoreProperties.getProperty("STORE_FILE")
            if (!storeFileProp.isNullOrEmpty()) {
                storeFile = file(storeFileProp)
                storePassword = keystoreProperties.getProperty("STORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("KEY_ALIAS")
                keyPassword = keystoreProperties.getProperty("KEY_PASSWORD")
            } else if (sharedKeystore.exists()) {
                storeFile = sharedKeystore
                storePassword = "epubpro_shared_key"
                keyAlias = "epubpro"
                keyPassword = "epubpro_shared_key"
            } else {
                val debugSigning = signingConfigs.getByName("debug")
                storeFile = debugSigning.storeFile
                storePassword = debugSigning.storePassword
                keyAlias = debugSigning.keyAlias
                keyPassword = debugSigning.keyPassword
            }
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":core:storage"))
    implementation(project(":core:playback"))
    implementation(project(":core:ai"))

    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:bookmark"))
    implementation(project(":feature:search"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:bookbible"))
    implementation(project(":feature:comic"))
    implementation(project(":feature:browser"))
    implementation(project(":core:tts"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    kapt(libs.androidx.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
}
