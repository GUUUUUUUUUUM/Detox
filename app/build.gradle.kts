plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mingming.detox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mingming.detox"
        minSdk = 26
        targetSdk = 35
        // GitHub Actions 실행 번호를 버전으로 써서, 앱이 새 빌드를 알아볼 수 있게 한다.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "build-$run"
    }

    // 모든 빌드를 같은 키로 서명해야 지우지 않고 덮어쓰기 업데이트가 된다.
    signingConfigs {
        create("personal") {
            storeFile = file("detox.jks")
            storePassword = "detox-personal"
            keyAlias = "detox"
            keyPassword = "detox-personal"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("personal") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
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
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
