plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("jacoco")
}
android {
    namespace = "com.bobbywasabi.overlayapp"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bobbywasabi.overlayapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "0.3.2-alpha.5"
    }
    buildFeatures { viewBinding = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    signingConfigs {
        // Item 51: production signing from environment (repo secrets in CI).
        // When the keystore is absent the release build falls back to the
        // debug key so local builds keep working.
        create("release") {
            val keystorePath = System.getenv("THROW_ASSISTANT_KEYSTORE_PATH")
            if (!keystorePath.isNullOrEmpty()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("THROW_ASSISTANT_STORE_PASSWORD")
                keyAlias = System.getenv("THROW_ASSISTANT_KEY_ALIAS")
                keyPassword = System.getenv("THROW_ASSISTANT_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
        }
        release {
            val keystorePath = System.getenv("THROW_ASSISTANT_KEYSTORE_PATH")
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        warningsAsErrors = true
        // Item 58: baseline keeps pre-existing warnings from failing the build;
        // new warnings still fail because warningsAsErrors stays true.
        baseline = file("lint-baseline.xml")
        // Keep this prototype on API 35 until newer target behavior is device-tested.
        // The time-dependent SDK upgrade advisory is not a code-correctness gate.
        disable += "OldTargetApi"
        // Remote dependency-update recommendations are not reproducible build gates.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

// Item 44: coverage floor. The JaCoCo agent runs on the unit-test task;
// `./gradlew :app:checkCoverage` fails if line coverage drops below the floor.
tasks.withType<Test>().configureEach {
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isEnabled = true
    }
}
tasks.register("jacocoTestReport", JacocoReport::class) {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    val buildDir = layout.buildDirectory.get().asFile
    val classDirs = files(
        fileTree("$buildDir/tmp/kotlin-classes/debug") { exclude("**/R.class", "**/R$*.class", "**/BuildConfig*") }
    )
    val sourceDirs = files("src/main/java")
    val execData = files("$buildDir/jacoco/testDebugUnitTest.exec")
    classDirectories.setFrom(classDirs)
    sourceDirectories.setFrom(sourceDirs)
    executionData.setFrom(execData)
}

tasks.register("checkCoverage") {
    dependsOn("jacocoTestReport")
    doLast {
        val xml = file("${layout.buildDirectory.get().asFile}/reports/jacoco/jacocoTestReport/jacocoTestReport.xml")
        require(xml.exists()) { "JaCoCo XML report missing at ${xml.path}" }
        val text = xml.readText()
        val line = Regex("<counter type=\"LINE\"[^>]*>").findAll(text).last()
        val missed = Regex("missed=\"(\\d+)\"").find(line.value)!!.groupValues[1].toInt()
        val covered = Regex("covered=\"(\\d+)\"").find(line.value)!!.groupValues[1].toInt()
        val ratio = covered.toDouble() / (missed + covered)
        println("Line coverage: ${"%.1f".format(ratio * 100)}% ($covered/${missed + covered})")
        require(ratio >= 0.20) { "Coverage floor is 20%, measured ${"%.1f".format(ratio * 100)}%" }
    }
}
