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
        maven { url = java.net.URI("https://m2repo.videolan.org/nexus/content/repositories/releases/") }
        maven { url = java.net.URI("https://jitpack.io") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PortalStreamPro"
include(":app")

