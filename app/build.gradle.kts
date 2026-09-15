plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kokoreader"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.kokoreader"
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "1.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                // 16KB page-size support (Pixel 9 / Android 15 requirement)
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests.all {
            it.systemProperty(
                "kokoreader.srcMain",
                File(projectDir, "src/main/java").absolutePath
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // ML Kit on-device text recognition (Latin)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // ONNX Runtime Android for Kokoro-82M INT8
    // 1.25.1 ships 16KB-aligned arm64 native libs (1.20.0 does not)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.25.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.5")

    testImplementation("junit:junit:4.13.2")
}
