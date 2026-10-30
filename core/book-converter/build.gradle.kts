plugins {
    id("epubpro.android.library")
}

android {
    namespace = "com.epubpro.core.bookconverter"

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c11"
                arguments += listOf(
                    "-DUSE_ENCRYPTION=OFF",
                    "-DUSE_XMLWRITER=ON",
                    "-DUSE_LIBXML2=OFF",
                    "-DUSE_ZLIB=OFF",
                    "-DUSE_MINIZ=ON",
                    "-DBUILD_SHARED_LIBS=OFF"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:comic"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)

    testImplementation(libs.junit4)
}
