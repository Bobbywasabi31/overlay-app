plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.bobbywasabi.overlayapp"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bobbywasabi.overlayapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1-alpha.2"
    }
    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        warningsAsErrors = true
        // Keep this prototype on API 35 until newer target behavior is device-tested.
        // The time-dependent SDK upgrade advisory is not a code-correctness gate.
        disable += "OldTargetApi"
        // Remote dependency-update recommendations are not reproducible build gates.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
