plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.aimeshvision"
    compileSdk = 36

    // Signing: a stable project keystore committed with the repo, so every
    // build (debug AND release) is signed with the same key and APKs install
    // as updates over each other. Fine for a personal tool; rotate before
    // any public release.
    signingConfigs {
        create("projectKey") {
            storeFile = rootProject.file("app/aimeshvision.keystore")
            storePassword = "aimeshvision2026"
            keyAlias = "aimeshvision"
            keyPassword = "aimeshvision2026"
        }
    }

    defaultConfig {
        applicationId = "com.example.aimeshvision"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Prevent TFLite from stripping GPU delegate JNI libs
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("projectKey")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("projectKey")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Needed for TFLite to include native .so libraries properly
    packaging {
        jniLibs.pickFirsts.add("**/*.so")
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // TensorFlow Lite
    implementation(libs.tflite)
    implementation(libs.tflite.gpu)
    implementation(libs.tflite.gpu.api)

    // UI layouts
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}