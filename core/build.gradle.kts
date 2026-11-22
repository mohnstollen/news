// Shared core of the two apps: feeds, database, sync, screens and podcast playback.
// The apps (":app" = News, ":podfang" = Podfang) add name, icon and which features they show.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/**
 * The Android runtimes Robolectric runs the tests on: SDK 34 (most tests) and 35 (the default).
 * One configuration each, or Gradle would treat them as two versions of one library and keep one.
 */
val robolectricSdk34: Configuration by configurations.creating { isTransitive = false }
val robolectricSdk35: Configuration by configurations.creating { isTransitive = false }
val robolectricJarsDir = layout.buildDirectory.dir("robolectric-jars")
val robolectricJars = tasks.register<Sync>("robolectricJars") {
    from(robolectricSdk34, robolectricSdk35)
    into(robolectricJarsDir)
}

android {
    namespace = "io.github.mohnstollen.news"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        targetSdk = 35
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // The Compose UI tests need a JVM each.
            it.setForkEvery(1)
            // Robolectric's Android runtime comes from Gradle (cached, with retries) instead of being
            // downloaded by Robolectric itself at test time, which fails when Maven Central refuses it.
            it.dependsOn(robolectricJars)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", robolectricJarsDir.get().asFile.absolutePath)
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // QR code login: camera without Google Play services, decoding with ZXing
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.3")
    // Login Flow v2: the Nextcloud login page as a Custom Tab on top of the app
    implementation("androidx.browser:browser:1.8.0")

    // Podcast playback: player, media session (notification, lock screen, headset) and OkHttp streaming
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    // Podcast covers: loading and caching images (also offline), without Google services
    // api: the apps' application classes inherit Coil's ImageLoaderFactory through NewsApp.
    api("io.coil-kt:coil-compose:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("net.dankito.readability4j:readability4j:1.0.8")

    robolectricSdk34("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
    robolectricSdk35("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
