plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.popow.popowmods"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.popow.popowmods"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
