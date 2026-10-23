plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}
android {
    namespace = "me.ailinux.pocket"
    compileSdk = 36
    defaultConfig {
        applicationId = "me.ailinux.pocket"
        minSdk = 33
        targetSdk = 35
        versionCode = 12
        versionName = "1.0.0-rc.2"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // GGML_BACKEND_DL scans nativeLibraryDir for actual .so files.
        // Modern Android installs uncompressed JNI libs directly from the APK
        // by default. Without physical extraction, zero CPU backends are found.
        jniLibs.useLegacyPackaging = true
    }
}
dependencies {
    implementation(libs.bundles.androidx)
    implementation(libs.material)
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation(project(":inference"))
    testImplementation(libs.junit)
    // Real org.json is necessary in local JVM protocol integration tests;
    // the Android test stub otherwise throws "Method not mocked".
    testImplementation("org.json:json:20240303")
}
