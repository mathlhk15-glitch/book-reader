plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.qreader.app"
    compileSdk = 35
    defaultConfig { applicationId = "com.qreader.app"; minSdk = 26; targetSdk = 35; versionCode = 4; versionName = "0.4.0-alpha" }
    signingConfigs {
        create("personalRelease") {
            val store = System.getenv("QREADER_KEYSTORE")
            if (!store.isNullOrBlank()) {
                storeFile = file(store)
                storePassword = System.getenv("QREADER_STORE_PASSWORD")
                keyAlias = System.getenv("QREADER_KEY_ALIAS")
                keyPassword = System.getenv("QREADER_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!System.getenv("QREADER_KEYSTORE").isNullOrBlank()) signingConfig = signingConfigs.getByName("personalRelease")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
}
