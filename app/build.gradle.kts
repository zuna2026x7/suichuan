plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val transferApiBase: String = (project.findProperty("transferApiBase") as String?) ?: ""

android {
    namespace = "cloud.suichuan.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "cloud.suichuan.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TRANSFER_API_BASE", "\"$transferApiBase\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    // Embedded barcode scanner (camera QR capture) for the receive screen.
    // 4.3.0 pulls zxing core 3.5.x, compatible with the pin above.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
}
