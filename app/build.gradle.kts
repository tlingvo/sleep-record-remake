plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "sleep.record"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "sleep.record"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "2.1.2"
        val publicKey = providers.gradleProperty("sleepPublicChatKey").orElse("sk-5hvOF4SWqKVLDYI3KNxGl7C0n2qp3kSPKgVLQqPmItOXT7eR").get()
        buildConfigField("String", "PUBLIC_CHAT_KEY", "\"${publicKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        testInstrumentationRunner = "sleep.record.ContractRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures { buildConfig = true }
    androidResources { noCompress += listOf("mp3", "wav") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
}

kotlin { compilerOptions { freeCompilerArgs.add("-Xcontext-parameters") } }
