import java.time.LocalDate
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing secrets: environment variables first, then a git-ignored keystore.properties.
// See the signing notes inside android { } below.
val localSigning = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { input -> load(input) }
}
fun secret(name: String): String? =
    (System.getenv(name) ?: localSigning.getProperty(name))?.takeIf { it.isNotBlank() }
val keystorePath: String? = secret("SIGNING_KEYSTORE_PATH")
val hasSigningKey = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "com.daniel.carscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.daniel.carscanner"
        minSdk = 26
        targetSdk = 34
        // Every GitHub build gets the next build number, so the app can tell when a newer one exists.
        // Local builds use 8, which is lower, so they never block an update from GitHub.
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = buildNumber ?: 8
        versionName = "2.4." + (buildNumber?.toString() ?: "local")
        // Build date shown on the About screen
        buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now()}\"")
    }
    // One permanent signing key for every build. Android only installs an update
    // over the existing app if both are signed with the same key.
    //
    // The key and its password are SECRET and never live in this repository.
    // They come from environment variables (GitHub Actions secrets, see
    // .github/workflows/build.yml) or from a local, git-ignored keystore.properties:
    //   SIGNING_KEYSTORE_PATH, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD
    // Without them the build still works, signed with Android's standard debug key
    // (fine for testing, but it can't update an app installed from a signed build).
    signingConfigs {
        if (hasSigningKey) create("daniel") {
            storeFile = file(keystorePath!!)
            storePassword = secret("SIGNING_STORE_PASSWORD")
            keyAlias = secret("SIGNING_KEY_ALIAS") ?: "daniels"
            keyPassword = secret("SIGNING_KEY_PASSWORD") ?: secret("SIGNING_STORE_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            if (hasSigningKey) signingConfig = signingConfigs.getByName("daniel")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasSigningKey) signingConfig = signingConfigs.getByName("daniel")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")   // daily background recall check

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // real JSON for unit tests (Android's is a stub there)
}
