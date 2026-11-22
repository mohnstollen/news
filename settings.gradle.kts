pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Robolectric's Android runtimes (about 200 MB each) only from Google's mirror of Maven Central:
        // Maven Central itself refuses these large downloads with "429 Too Many Requests" at times.
        exclusiveContent {
            forRepository {
                maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
            }
            filter { includeModule("org.robolectric", "android-all-instrumented") }
        }
        mavenCentral()
    }
}
rootProject.name = "News"
include(":core")
// News: the RSS reader. Podfang: the podcast app. Both on top of ":core".
include(":app")
include(":podfang")
