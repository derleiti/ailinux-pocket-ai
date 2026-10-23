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
        versionCode = 2
        versionName = "0.1.0-alpha.2"
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
    }
}
dependencies {
    implementation(libs.bundles.androidx)
    implementation(libs.material)
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation(project(":inference"))
    testImplementation(libs.junit)
}
