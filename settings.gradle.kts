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
        maven { url = uri("https://m2repo.videolan.org/nexus/content/repositories/releases/") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PortalStreamPro"
include(":app")
