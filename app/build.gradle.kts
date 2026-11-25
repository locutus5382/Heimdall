plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.locutus5382.heimdall"
    compileSdk = 36
    // CI can run identical instrumentation tests against the actual R8
    // optimized QA APK, not only the unshrunk debug variant.
    testBuildType = providers.gradleProperty("heimdallAndroidTestBuildType")
        .orElse("debug").get()
    defaultConfig {
        applicationId = "com.locutus5382.heimdall"
        minSdk = 31
        targetSdk = 36
        versionCode = 15
        versionName = "0.8.6-odin-pretest-stability-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Only the minified instrumentation APK references JDK annotation APIs.
        testProguardFiles("test-proguard-rules.pro")
    }
    buildTypes {
        getByName("release") {
            // Production builds use R8; keep the separately signed release
            // workflow distinct from CI's temporary debug test certificate.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro")
        }
        create("releaseQa") {
            // CI-only installable mirror of the production release R8 rules.
            // Never publish this build: the key is ephemeral and differs per CI runner.
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        create("leanDebug") {
            // Installable, R8-optimized QA APK signed by the normal debug key.
            // Never distribute it as a release; GitHub runners rotate keys.
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro", "lean-instrumentation-abi.pro")
            matchingFallbacks += listOf("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
}
dependencies {
    implementation(project(":engine"))
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Gradle test variants do not inherit the main Compose BOM.
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    debugImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // R8 also minifies the instrumented leanDebug test APK. These annotations
    // are referenced by AndroidX Test's runtime checks but are otherwise absent.
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.28.0")
    // AndroidJUnitRunner invokes androidx.tracing.Trace before any test runs.
    // Match the runtime-constrained AndroidX Activity 1.9.1 version; test R8
    // keeps the instrumentation implementation via test-proguard-rules.pro.
    androidTestImplementation("androidx.tracing:tracing:1.0.0")
    // The optimized app strips unused Kotlin runtime classes, while the
    // separately minified AndroidX instrumentation runner still needs LazyKt.
    // Keep the test APK self-contained rather than disabling app shrinking.
    androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.25")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
