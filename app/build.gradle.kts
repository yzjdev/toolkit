import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// release 签名配置：从项目根目录 keystore.properties 读取（该文件不入库）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "app.toolkit"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.toolkit"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // 只打包 64 位原生库，去掉 armeabi-v7a / x86
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // debug 与 release 用不同包名，可同时安装；应用名见 src/debug/res/values/strings.xml
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // 只保留实际支持的语言：依赖库自带 30+ 种翻译，全部丢弃可显著缩小 resources.arsc
        localeFilters += listOf("zh", "en")
    }

    packaging {
        jniLibs {
            // 支持 Android 10 以下设备时 priv-kit 需要 legacy 打包
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                // 依赖库附带的许可证文本，运行时无用。
                // 实际路径形如 META-INF/androidx/lifecycle/lifecycle-common/LICENSE.txt，
                // 单星号不跨目录，必须用 ** 才能命中。
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/**/LICENSE*",
                "META-INF/**/NOTICE*",
                // kotlinx-coroutines 调试探针，仅调试器需要
                "DebugProbesKt.bin",
            )
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM：统一所有 Compose 依赖版本（material3 1.4.0 等）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.priv.kit.priv.ui)
    implementation(libs.hiddenapibypass)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
