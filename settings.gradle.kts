pluginManagement {
    plugins {
        kotlin("jvm") version "2.1.10"
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

rootProject.name = "nuprocess-shim"
