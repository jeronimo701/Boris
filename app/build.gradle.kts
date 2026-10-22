plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val borisVersionName = "1.0.13"

android {
    namespace = "ru.nanospora.boris"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.nanospora.boris"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = borisVersionName
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}

tasks.register<Copy>("packageDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/boris"))
    rename { "Boris-$borisVersionName-debug.apk" }
}
