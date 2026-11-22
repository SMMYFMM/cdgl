import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名取值顺序：
//   1. 环境变量 KEYSTORE_FILE（CI 可改用 Secrets 注入）
//   2. 仓库内置的 signing/chargefloat.jks（保证 CI 与本地构建签名一致）
//   3. 都没有则回退 debug 签名
val envKs = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
val builtinKs = rootProject.file("signing/chargefloat.jks").takeIf { it.exists() }
val ksFile = envKs ?: builtinKs
// 注意：GitHub Actions 未配置的 Secret 会注入空字符串，这里必须按「空串也算未设置」处理
val ksStorePass = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
val ksKeyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "androiddebugkey"
val ksKeyPass = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: ksStorePass
val hasReleaseKey = ksFile != null

android {
    namespace = "com.k80.chargefloat"
    compileSdk = 35

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = ksFile
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
        versionCode = 9
        versionName = "1.8"
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
