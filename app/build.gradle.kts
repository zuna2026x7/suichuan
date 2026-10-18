plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val transferApiBase: String = (project.findProperty("transferApiBase") as String?) ?: ""

// Version: the release workflow passes -PappVersionName=1.4 -PappVersionCode=10400
// (derived from the git tag v1.4). Local/CI debug builds use the defaults.
// Before this, every release shipped as versionName 0.1.0 / versionCode 1,
// so no in-app update check could tell versions apart.
val appVersionName: String = (project.findProperty("appVersionName") as String?) ?: "1.4"
val appVersionCode: Int = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 10400

// GitHub repo whose Releases the in-app update check reads.
val updateRepo: String = (project.findProperty("updateRepo") as String?) ?: "zuna2026x7/suichuan"

// Release signing comes only from the environment (GitHub Actions secrets),
// never from the repo. Without it, release builds stay unsigned and CI
// keeps producing debug APKs as before.
val releaseKeystore: String? = System.getenv("SUICHUAN_KEYSTORE_PATH")

android {
    namespace = "cloud.suichuan.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "cloud.suichuan.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TRANSFER_API_BASE", "\"$transferApiBase\"")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SUICHUAN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SUICHUAN_KEY_ALIAS")
                keyPassword = System.getenv("SUICHUAN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
