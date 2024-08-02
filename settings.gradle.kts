pluginManagement {
    plugins {
        kotlin("jvm") version "2.0.0"
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

rootProject.name = "nuprocess-shim"
