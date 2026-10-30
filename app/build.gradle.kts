plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.dhangofa.xiaomicnapfix"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.dhangofa.xiaomicnapfix"
        minSdk = 28
        targetSdk = 37
        versionCode = 20
        versionName = "1.0.2"
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
