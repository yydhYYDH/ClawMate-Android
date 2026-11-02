plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.clawmate.platform"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Match versions already selected by the Compose host; keep both hosts on
    // one dependency graph when these engines are consumed independently.
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.20")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.20")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-common:1.9.24")
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.exifinterface)
    implementation(libs.okhttp)
    implementation("com.squareup.okio:okio:3.8.0")
    implementation(libs.mlkit.text.recognition.chinese)
    // Bundled person mask; no Play-services model download or image upload.
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
