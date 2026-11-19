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
        versionCode = 7
        versionName = "0.6.0-h1-h4-qa"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        create("leanDebug") {
            // Installable, R8-optimized QA APK signed by the normal debug key.
            // Never distribute it as a release; GitHub runners rotate keys.
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro")
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Gradle test variants do not inherit the main Compose BOM.
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    debugImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
