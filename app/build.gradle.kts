// News: the RSS reader. Keeps the application id of the earlier single app, so it updates in place.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * The version of News. A release is the tag "news-v<appVersion>" (see .github/workflows/release.yml);
 * raise it before tagging. The version code follows from it, so it always grows.
 */
val appVersion = "1.9"

/** "1.2.3" → 10203: two digits each for minor and patch. */
fun versionCodeOf(name: String): Int = name.split(".").map(String::toInt).let {
    it.getOrElse(0) { 0 } * 10_000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 }
}

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.mohnstollen.news.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.mohnstollen.news"
        minSdk = 26
        targetSdk = 35
        versionName = appVersion
        versionCode = versionCodeOf(appVersion)
    }

    // Release signing from the environment (the release workflow's secrets); without it, unsigned.
    signingConfigs {
        val keystore = env("RELEASE_KEYSTORE")?.let(::file)?.takeIf { it.isFile }
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = env("RELEASE_STORE_PASSWORD") ?: "android"
                keyAlias = env("RELEASE_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = env("RELEASE_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))
}
