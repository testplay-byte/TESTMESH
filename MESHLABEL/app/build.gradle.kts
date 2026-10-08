plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.meshlabel"
    compileSdk = 36

    // No release keystore: this is a dataset-authoring tool, builds install
    // as debug; CI still produces a (unsigned) release variant artifact.
    defaultConfig {
        applicationId = "com.example.meshlabel"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64-v8a ONLY (user directive, same as the sibling app):
        // filters the MediaPipe tasks-vision JNI libs to the single device
        // family in use - noticeably smaller APK.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // Debug is auto-signed with the platform debug key.
        }
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)

    // MediaPipe interactive image segmentation ("Magic Touch": scribble
    // strokes -> mask). Hosted on google() — see libs.versions.toml.
    implementation(libs.mediapipe.tasks.vision)

    testImplementation(libs.junit)
    // org.json is part of the Android runtime but NOT the JVM unit-test
    // classpath — needed so LabelMeJson tests run on the host JVM.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
