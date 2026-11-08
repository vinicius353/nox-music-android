import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val signingKeyFile=System.getenv("NOX_KEYSTORE_PATH")
val signingStorePassword=System.getenv("NOX_KEYSTORE_PASSWORD")
val signingAlias=System.getenv("NOX_KEY_ALIAS")
val signingKeyPassword=System.getenv("NOX_KEY_PASSWORD")
val hasSigningKey=listOf(signingKeyFile,signingStorePassword,signingAlias,signingKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    signingConfigs {
        if(hasSigningKey) {
            create("noxPersistent") {
                storeFile=file(signingKeyFile!!)
                storePassword=signingStorePassword
                keyAlias=signingAlias
                keyPassword=signingKeyPassword
            }
        }
    }
    namespace = "com.nox.music"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.nox.music"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0-alpha"
    }
    buildTypes {
        getByName("debug") {
            if(hasSigningKey) signingConfig=signingConfigs.getByName("noxPersistent")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":innertube"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("io.coil-kt.coil3:coil:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
