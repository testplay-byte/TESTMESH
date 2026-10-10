plugins {
    alias(libs.plugins.android.application)
    // Compose compiler — AGP 9 compiles Kotlin itself (built-in Kotlin,
    // same as the sibling apps); only the compiler plugin is added here.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.testplaybyte.loom"
    compileSdk = 36

    // Loom ships without a release keystore: CI signs debug builds with the
    // committed project key (so every APK installs as an update), and the
    // release variant stays unsigned. Same policy as the old MESHLABEL app.
    signingConfigs {
        create("projectKey") {
            storeFile = rootProject.file("app/loom.keystore")
            storePassword = "loom2026"
            keyAlias = "loom"
            keyPassword = "loom2026"
        }
    }

    defaultConfig {
        applicationId = "com.testplaybyte.loom"
        // Spec: minSdk 26, target 35 (docs/06-android-guide.md §1).
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64-v8a ONLY — the single device family in use across the repo
        // (re-add ABIs here if another device family is ever needed).
        ndk {
            abiFilters += listOf("arm64-v8a")
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

    buildFeatures {
        compose = true
    }

    androidResources {
        // Keep the Magic Touch model uncompressed so MediaPipe can mmap it
        // (compressed assets pay a full decompression on every model load).
        noCompress += "task"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)
    // Magic Touch smart selection (MediaPipe InteractiveSegmenter).
    implementation(libs.mediapipe.tasks.vision)

    // Compose — BOM-managed versions (docs/06-android-guide.md §1).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    // org.json is part of the Android runtime but NOT the JVM unit-test
    // classpath — needed so the JSON codec tests run on the host JVM.
    testImplementation(libs.json)
}
