import java.time.LocalDate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.daniel.carscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.daniel.carscanner"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "2.2.2"
        // Build date shown on the About screen
        buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now()}\"")
    }
    // One permanent signing key for every build. Android only installs an update
    // over the existing app if both are signed with the same key; without this,
    // each GitHub build got a new random key and couldn't update the installed app.
    signingConfigs {
        create("daniel") {
            storeFile = file("daniels-scanner.keystore")
            storePassword = "QaeIbBQ35vWYGGmMMEDnWtFd"
            keyAlias = "daniels"
            keyPassword = "QaeIbBQ35vWYGGmMMEDnWtFd"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("daniel") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("daniel")
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
}
