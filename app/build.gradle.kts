plugins {
    id("com.android.application")
}

android {
    namespace = "com.dhangofa.xiaomicnapfix"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dhangofa.xiaomicnapfix"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.1-cnapfix"
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
