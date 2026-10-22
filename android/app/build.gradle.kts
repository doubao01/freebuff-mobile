import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 读取 android/keystore.properties(若存在)。该文件只存在于本机 / CI 密钥挂载中, 不入库。
// 存在但字段缺失时直接报错, 避免「以为是正式签名、实际用的是 debug 证书」。
val keystorePropsFile = rootProject.file("keystore.properties")
val releaseKeystoreProps: Properties? = if (keystorePropsFile.exists()) {
    Properties().apply { keystorePropsFile.inputStream().use { load(it) } }.also { props ->
        val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .filter { props.getProperty(it).isNullOrBlank() }
        check(missing.isEmpty()) {
            "keystore.properties 缺少必要字段: ${missing.joinToString()} —— 见 docs/build-and-release.md"
        }
    }
} else {
    null
}

// 版本号唯一来源:android/version.properties。app 的 versionName/versionCode 与 core:model 的
// LATEST_VERSION 都由它派生,发版只改那一个文件(标签必须等于 versionName,release.yml 会硬校验)。
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    check(versionPropsFile.exists()) { "缺少 android/version.properties(版本号唯一来源)" }
    versionPropsFile.inputStream().use { load(it) }
}
val appVersionName = versionProps.getProperty("versionName").orEmpty().trim()
val appVersionCode = versionProps.getProperty("versionCode").orEmpty().trim().toIntOrNull() ?: 0
check(Regex("\\d+\\.\\d+\\.\\d+").matches(appVersionName)) {
    "version.properties 的 versionName 必须是 x.y.z 形态,当前: '$appVersionName'"
}
check(appVersionCode > 0) { "version.properties 的 versionCode 必须是正整数,当前: '$appVersionCode'" }

// 生产配置注入:优先 android/local.properties(不入库),其次环境变量(CI Secrets/自建打包)。
// 两者都没有时留空 —— 未配置的官方能力会在 UI 明确提示,不会静默回退到演示数据。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun configValue(propKey: String, envKey: String): String =
    (localProps.getProperty(propKey) ?: System.getenv(envKey) ?: "").trim()

val gatewayBaseUrl = configValue("freebuff.gatewayBaseUrl", "FREEBUFF_GATEWAY_BASE_URL")
val githubClientId = configValue("freebuff.githubOauthClientId", "FREEBUFF_GITHUB_OAUTH_CLIENT_ID")
// 更新源默认值:本仓库(公开)的 dist/update.json —— 每次发版由 release.yml 自动刷新并回推 master,
// 匿名可读,无需任何密钥。要换成自建地址时设 freebuff.updateUrl / FREEBUFF_UPDATE_URL。
val updateUrl = configValue("freebuff.updateUrl", "FREEBUFF_UPDATE_URL")
    .ifBlank { "https://raw.githubusercontent.com/doubao01/freebuff-mobile/master/dist/update.json" }

android {
    namespace = "com.freebuff.mobile"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.freebuff.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // 生产接入配置(值来自 local.properties / 环境变量,见文件顶部):
        // - freebuff.gatewayBaseUrl / FREEBUFF_GATEWAY_BASE_URL
        //     官方模型网关根地址,如 https://api.example.com/v1;留空 = 未配置,官方模型不可用
        // - freebuff.githubOauthClientId / FREEBUFF_GITHUB_OAUTH_CLIENT_ID
        //     GitHub OAuth App 的 client_id;留空 = Git 账号接入不可用(设置页会提示)
        // - freebuff.updateUrl / FREEBUFF_UPDATE_URL
        //     版本检查 JSON 地址;默认指向本仓库(公开)的 dist/update.json,发版工作流自动刷新:
        //     {"version":"1.0.0","notes":["..."],"url":"...releases/latest"}
        //     checkForUpdate 拉取 version 与当前 BuildConfig.VERSION_NAME 比较。
        buildConfigField("String", "DEFAULT_GATEWAY_BASE_URL", "\"$gatewayBaseUrl\"")
        buildConfigField("String", "GITHUB_OAUTH_CLIENT_ID", "\"$githubClientId\"")
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }

    // 正式签名: 若 android/keystore.properties 存在, 用自有 keystore 签 release;
    // 否则回退 debug 签名 —— 保证任何环境都能产出可安装的包, 但该包不可上架。
    // keystore.properties 与 *.jks 都已 gitignore, 口令绝不入库。
    signingConfigs {
        if (releaseKeystoreProps != null) {
            create("release") {
                storeFile = file(releaseKeystoreProps.getProperty("storeFile"))
                storePassword = releaseKeystoreProps.getProperty("storePassword")
                keyAlias = releaseKeystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseKeystoreProps != null) {
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
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:settings"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
