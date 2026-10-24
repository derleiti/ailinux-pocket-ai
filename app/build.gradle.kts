plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}
// Production signing is opt-in. The private keystore comes from a protected
// local environment or protected GitHub Environment secrets, never source.
val releaseEnvKeys = listOf(
    "AILINUX_RELEASE_KEYSTORE_FILE",
    "AILINUX_RELEASE_STORE_PASSWORD",
    "AILINUX_RELEASE_KEY_ALIAS",
    "AILINUX_RELEASE_KEY_PASSWORD"
)
val releaseEnv = releaseEnvKeys.associateWith { System.getenv(it) }
val releaseSigningPresent = releaseEnv.values.count { !it.isNullOrBlank() }
if (releaseSigningPresent in 1..3) {
    throw GradleException("Incomplete protected release signing configuration")
}

android {
    namespace = "me.ailinux.pocket"
    compileSdk = 36
    defaultConfig {
        applicationId = "me.ailinux.pocket"
        minSdk = 33
        targetSdk = 35
        versionCode = 31
        versionName = "2.1.0-RC6"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        if (releaseSigningPresent == releaseEnvKeys.size) {
            create("ailinuxRelease") {
                storeFile = file(releaseEnv.getValue("AILINUX_RELEASE_KEYSTORE_FILE")!!)
                storePassword = releaseEnv.getValue("AILINUX_RELEASE_STORE_PASSWORD")!!
                keyAlias = releaseEnv.getValue("AILINUX_RELEASE_KEY_ALIAS")!!
                keyPassword = releaseEnv.getValue("AILINUX_RELEASE_KEY_PASSWORD")!!
            }
        }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            if (releaseSigningPresent == releaseEnvKeys.size) {
                signingConfig = signingConfigs.getByName("ailinuxRelease")
            }
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
        // Google's LiteRT-LM TPU sample ships these duplicate vendor libs.
        jniLibs.pickFirsts += setOf(
            "**/libLiteRt.so",
            "**/libLiteRtDispatch_GoogleTensor.so",
            "**/libLiteRtClGlAccelerator.so"
        )
    }
}
dependencies {
    implementation(libs.bundles.androidx)
    implementation(libs.material)
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation(project(":inference"))
    // Tested against the official Google Tensor G5 TPU sample dependency set.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.12.0")
    implementation("com.google.ai.edge.litert:litert:2.3.0")
    testImplementation(libs.junit)
    // Real org.json is necessary in local JVM protocol integration tests;
    // the Android test stub otherwise throws "Method not mocked".
    testImplementation("org.json:json:20240303")
}
