plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Required by the Samsung Health Data API: its request/response classes are
    // @Parcelize, and Parceler must be on the compile classpath. Applied by id
    // without a version because the Kotlin Gradle plugin already ships it.
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "com.example.watchbridge"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.watchbridge"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Samsung Health Data API. The AAR is proprietary and deliberately NOT
    // committed. Download it from your Samsung developer account and place it at
    // watch/app/libs/samsung-health-data-api-1.1.0.aar before building.
    // See the repository README for details.
    implementation(files("libs/samsung-health-data-api-1.1.0.aar"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling)
    implementation(libs.core.splashscreen)
    implementation(libs.guava)
    implementation(libs.play.services.wearable)
    implementation(libs.protolayout)
    implementation(libs.protolayout.material3)
    implementation(libs.tiles)
    implementation(libs.tiles.tooling.preview)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.watchface.complications.data.source.ktx)
    implementation(libs.wear.tooling.preview)
    androidTestImplementation(platform(libs.compose.bom))
    testImplementation("junit:junit:4.13.2")
    // org.json is a stub in android.jar; unit tests need a real one.
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.tiles.renderer)
    debugImplementation(libs.tiles.tooling)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
}