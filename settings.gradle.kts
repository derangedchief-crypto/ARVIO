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
        mavenCentral()
        // OwnTV's public GPL-3.0 IPTV core and dual-engine playback artifacts.
        // Kept separate from Maven Central because the project publishes its own
        // signed Android artifacts from its source repository.
        maven {
            name = "OwnTV"
            url = uri("https://ahxn00.github.io/OwnTV_Core/maven")
            content { includeGroup("tv.own.owntv") }
        }
        // Jellyfin's FFmpeg extension for Media3
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://s01.oss.sonatype.org/content/repositories/releases/") }
    }
}

rootProject.name = "ARVIO"
include(":app")
include(":benchmark")
