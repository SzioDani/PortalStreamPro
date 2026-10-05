plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    kotlin("android") version "1.9.21" apply false
    kotlin("jvm") version "1.9.21" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.4"
    id("org.sonarqube") version "4.4.1.3373"
}

sonarqube {
    properties {
        property("sonar.projectKey", "portalstream_portalstream-pro")
        property("sonar.organization", "tuo-org")
        property("sonar.host.url", "https://sonarcloud.io")
    }
}

detekt {
    toolVersion = "1.23.4"
    config = files("detekt.yml")
    parallel = true
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
