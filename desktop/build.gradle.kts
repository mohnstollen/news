import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/**
 * Data layer shared with the Android app: database schema, feed loading, OPML and Nextcloud sync
 * are compiled from the app's sources, so both versions behave the same and read the same sync file.
 * The desktop module supplies the platform parts (settings store, background updates, UI).
 */
val sharedSources = tasks.register<Sync>("sharedSources") {
    from("../app/src/main/java") {
        include(
            "io/github/mohnstollen/news/data/Database.kt",
            "io/github/mohnstollen/news/data/FeedParser.kt",
            "io/github/mohnstollen/news/data/Network.kt",
            "io/github/mohnstollen/news/data/Opml.kt",
            "io/github/mohnstollen/news/data/Repository.kt",
            "io/github/mohnstollen/news/data/Sync.kt",
        )
    }
    into(layout.buildDirectory.dir("generated/shared"))
}

sourceSets.main {
    kotlin.srcDir(sharedSources)
}

ksp {
    arg("room.generateKotlin", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.sqlite:sqlite-bundled:2.5.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("net.dankito.readability4j:readability4j:1.0.8")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

compose.desktop {
    application {
        mainClass = "io.github.mohnstollen.news.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "news-reader"
            packageVersion = "1.4.0"
            description = "RSS- und Atom-Reader"
            vendor = "mohnstollen"
            // As reported by suggestRuntimeModules; keeps the bundled Java runtime small.
            modules("java.instrument", "jdk.unsupported")
            linux {
                iconFile.set(project.file("icon.png"))
                shortcut = true
                menuGroup = "Network;News"
                appCategory = "Network"
                debMaintainer = "mohnstollen@users.noreply.github.com"
            }
        }
    }
}
