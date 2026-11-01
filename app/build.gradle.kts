import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zcodeapp.remote"
    buildToolsVersion = "36.1.0"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    /**
     * release 签名：环境变量优先，其次 local.properties（已 gitignore，不入库）。
     * keystore/ 已在 .gitignore 排除，密钥不随仓库分发，仓库内不内嵌任何口令。
     * keystore 与口令任一缺失时该配置为空，release 构建产出未签名包，此时不应分发。
     *
     * 环境变量：ZR_KEYSTORE / ZR_KEYSTORE_PASSWORD / ZR_KEY_ALIAS / ZR_KEY_PASSWORD
     * local.properties 键：zr.keystorePath / zr.keystorePassword / zr.keyAlias / zr.keyPassword
     */
    val localSigning = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }
            ?.inputStream()?.use { load(it) }
    }
    fun signingEnv(name: String, localKey: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: localSigning.getProperty(localKey)?.takeIf { it.isNotBlank() }

    /**
     * 日志上传配置 —— token **只注入 debug 构建**，目标仓库（owner/repo）注入两种构建。
     *
     * 为什么必须区分构建类型：release 包会发布到公开渠道，任何人下载解包就能拿到常量。
     * 这个 token 是账号级权限（可读写全部私有仓库），一旦泄漏等于把所有项目的日志仓库公开。
     * 所以 release 恒为空串，用户要上传就在设置页粘贴一次，token 只存本机 SharedPreferences。
     *
     * debug 构建注入的意义：本机自测时不必每次手输，且 debug 包不外发。
     * 仓库 owner/repo 不是凭据，但开源构建默认为空——自行部署时在 local.properties 配
     * zr.logOwner / zr.logRepo 指向自己的私有仓库。
     */
    val logToken = localSigning.getProperty("zr.logUploadToken", "").trim()
    val logOwner = localSigning.getProperty("zr.logOwner", "").trim()
    val logRepo = localSigning.getProperty("zr.logRepo", "").trim()
    val escapedLogToken = logToken.replace("\\", "\\\\").replace("\"", "\\\"")

    // 更新源：默认走 GitHub Release（公开资产匿名可下载，无需凭据）；
    // 维护者自有渠道在 local.properties 配 zr.updateManifestUrl / zr.updateFallbackUrl 覆盖。
    val updateManifestUrl = localSigning.getProperty("zr.updateManifestUrl")?.trim().takeIf { !it.isNullOrBlank() }
        ?: "https://github.com/a1286743184/ZcodeGo/releases/latest/download/latest.json"
    val updateFallbackUrl = localSigning.getProperty("zr.updateFallbackUrl")?.trim().takeIf { !it.isNullOrBlank() }
        ?: "https://github.com/a1286743184/ZcodeGo/releases/latest/download/zcodego-latest.apk"

    signingConfigs {
        create("release") {
            val storePath = signingEnv("ZR_KEYSTORE", "zr.keystorePath")
                ?: File(rootDir, "keystore/zcoderemote-release.jks").absolutePath
            val store = File(storePath)
            val storePw = signingEnv("ZR_KEYSTORE_PASSWORD", "zr.keystorePassword")
            val keyPw = signingEnv("ZR_KEY_PASSWORD", "zr.keyPassword")
            if (store.exists() && storePw != null && keyPw != null) {
                storeFile = store
                storePassword = storePw
                keyAlias = signingEnv("ZR_KEY_ALIAS", "zr.keyAlias") ?: "zcoderemote"
                keyPassword = keyPw
            }
        }
    }

    defaultConfig {
        applicationId = "com.zcodeapp.remote"
        minSdk = 26
        targetSdk = 36
        versionCode = 26
        versionName = "0.4.4"
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        buildConfigField("String", "REMOTE_CONTROL_DEFAULT_URL", "\"https://zcode.z.ai/remote/v4\"")
        buildConfigField("String", "LOG_UPLOAD_OWNER", "\"$logOwner\"")
        buildConfigField("String", "LOG_UPLOAD_REPO", "\"$logRepo\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
        buildConfigField("String", "UPDATE_FALLBACK_URL", "\"$updateFallbackUrl\"")
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            buildConfigField("String", "LOG_UPLOAD_TOKEN", "\"$escapedLogToken\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 仅在签名配置完整时启用；否则产出未签名包（不应分发）。
            signingConfigs.getByName("release").storeFile?.let {
                signingConfig = signingConfigs.getByName("release")
            }
            // 恒为空：release 包对外分发，绝不能带凭据。
            buildConfigField("String", "LOG_UPLOAD_TOKEN", "\"\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
