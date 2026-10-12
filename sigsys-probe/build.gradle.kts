plugins {
    id("com.android.application")
}

android {
    namespace = "me.phie.tawc.sigsysprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "me.phie.tawc.sigsysprobe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            // The existing reviewed CI certificate; never replace the TAWC app.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    sourceSets.getByName("main") {
        assets.srcDir("build/generated/probeAssets")
        jniLibs.srcDir("build/generated/probeJni")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
