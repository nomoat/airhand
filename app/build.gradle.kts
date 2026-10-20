plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ai.rebyte.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "ai.rebyte.mobile"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    // Release credentials are supplied locally; no signing secrets belong in the repository.
    val releaseStore = System.getenv("AIRHAND_SIGNING_STORE_FILE")
    if (!releaseStore.isNullOrBlank()) {
        signingConfigs {
            create("distribution") {
                storeFile = file(releaseStore)
                storePassword = requireNotNull(System.getenv("AIRHAND_SIGNING_STORE_PASSWORD"))
                keyAlias = requireNotNull(System.getenv("AIRHAND_SIGNING_KEY_ALIAS"))
                keyPassword = requireNotNull(System.getenv("AIRHAND_SIGNING_KEY_PASSWORD"))
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("distribution")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
