plugins {
    id("com.android.application")
}

android {
    namespace = "com.adrianramos.catprint"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.adrianramos.catprint"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideloaded app: sign release builds with the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
