pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = java.net.URI("https://jitpack.io") }
        exclusiveContent {
            forRepository {
                maven {
                    name = "LocalVendorMaven"
                    url = java.net.URI(rootDir.resolve("third_party/maven").toURI().toString())
                }
            }
            filter {
                includeModule("com.epubpro.vendor", "sherpa-onnx")
            }
        }
    }
}

rootProject.name = "EpubPro"

include(":app")
include(":domain")
include(":core:common")
include(":core:designsystem")
include(":core:database")
include(":core:storage")
include(":feature:library")
include(":feature:reader")
include(":feature:bookmark")
include(":feature:search")
include(":feature:profile")
include(":feature:bookbible")
include(":feature:comic")
include(":feature:browser")
include(":core:tts")
include(":core:ai")
include(":core:epub")
include(":core:book-converter")
include(":core:comic")
include(":core:webnovel")
include(":core:convert")
include(":core:ocr")
include(":core:reader-renderer")
include(":core:playback")
