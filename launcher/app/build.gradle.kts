plugins {
    id("com.android.application")
}

android {
    namespace = "com.tom.magify.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tom.magify.launcher"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.collection:collection-ktx:1.4.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
