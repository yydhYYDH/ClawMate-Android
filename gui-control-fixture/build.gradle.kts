// Disposable SDK-only test actor. This is not a third product/frontend host.
plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.clawmate.gui.fixture"
    compileSdk = 34
    defaultConfig { applicationId = "com.clawmate.gui.fixture"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "test-only" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
