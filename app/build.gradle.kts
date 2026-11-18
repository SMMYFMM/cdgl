import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息由环境变量注入（GitHub Actions 用 Secrets 提供）；
// 本地或未配置时自动回退到 debug 签名，保证任何环境下都能出包。
val ksPath = System.getenv("KEYSTORE_FILE")
val ksStorePass = System.getenv("KEYSTORE_PASSWORD")
val ksKeyAlias = System.getenv("KEY_ALIAS") ?: "chargefloat"
val ksKeyPass = System.getenv("KEY_PASSWORD") ?: ksStorePass
val hasReleaseKey = !ksPath.isNullOrBlank() && File(ksPath).exists() && !ksStorePass.isNullOrBlank()

android {
    namespace = "com.k80.chargefloat"
    compileSdk = 35

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = File(ksPath!!)
                storePassword = ksStorePass
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPass
            }
        }
    }

    defaultConfig {
        applicationId = "com.k80.chargefloat"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
