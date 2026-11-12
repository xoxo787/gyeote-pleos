import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: same rule as :app. Reads a properties file kept outside the repo
// (default ~/.keys/passer/signing.properties, override with PASSER_SIGNING_PROPS).
// If the file is missing, release stays unsigned.
val signingPropsFile = File(
    System.getenv("PASSER_SIGNING_PROPS")
        ?: "${System.getProperty("user.home")}/.keys/passer/signing.properties"
)
val signingProps: Properties? = if (signingPropsFile.isFile) {
    Properties().also { p -> FileInputStream(signingPropsFile).use { s -> p.load(s) } }
} else null

android {
    namespace = "ai.gyeote.buzzer"
    compileSdk = 36
    defaultConfig {
        applicationId = "ai.gyeote.buzzer"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (signingProps != null) {
        signingConfigs {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug { isDebuggable = true }
        release {
            isMinifyEnabled = false
            if (signingProps != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = false }
    lint { abortOnError = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("androidx.activity:activity:1.10.1")
    testImplementation("junit:junit:4.13.2")
}
