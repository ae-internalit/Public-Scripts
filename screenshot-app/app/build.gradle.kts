plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.doubletap.screenshot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.doubletap.screenshot"
        minSdk = 28
        targetSdk = 34
        versionCode = 16
        versionName = "6.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Unbundled: the OCR model ships via Play Services instead of being baked
    // into the APK, which keeps the download roughly 30MB smaller. Same API.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
}
