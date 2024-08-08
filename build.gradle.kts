import name.remal.gradle_plugins.dsl.extensions.applyPlugin
import name.remal.gradle_plugins.plugins.publish.ossrh.RepositoryHandlerOssrhExtension
import org.jetbrains.dokka.DokkaConfiguration
import org.jetbrains.dokka.Platform
import org.jetbrains.dokka.gradle.DokkaTask
import java.util.*

buildscript {
    repositories { mavenCentral() }

    dependencies {
        classpath(libs.remalGradlePlugins)
    }
}

plugins {
    kotlin("jvm")

    alias(libs.plugins.detekt)
    jacoco

    alias(libs.plugins.dokka)
    alias(libs.plugins.license)
    alias(libs.plugins.release)
    `maven-publish`
}

dependencies {
    implementation(libs.slf4j)

    testImplementation(libs.hamcrest)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.logback)
}

kotlin {
    jvmToolchain(21)
}

tasks.compileJava {
    options.compilerArgumentProviders.add(CommandLineArgumentProvider {
        listOf("--patch-module", "com.zaxxer.nuprocess=${sourceSets["main"].output.asPath}")
    })
}

tasks.test {
    useJUnit()
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.withType<DokkaTask> {
    dokkaSourceSets {
        configureEach {
            documentedVisibilities.set(setOf(DokkaConfiguration.Visibility.PUBLIC))
            jdkVersion.set(21)
            includes.from(project.files(), "packages.md")
            platform.set(Platform.jvm)
        }
    }
}

val javadocJar by tasks.register<Jar>("javadocJar") {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    dependsOn(tasks.dokkaJavadoc)

    from(tasks.dokkaJavadoc.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

license {
    exclude("**/*.exe")

    header = file("LICENSE")
    skipExistingHeaders = true

    ext {
        set("year", Calendar.getInstance().get(Calendar.YEAR))
    }
}

release {
    tagTemplate = "v\$version"
}

val sourcesJar by tasks.register<Jar>("sourcesJar") {
    from(sourceSets.main.get().allSource)
    archiveClassifier.set("sources")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "${project.group}"
            artifactId = project.name
            version = "${project.version}"

            from(components["java"])

            artifact(sourcesJar)
            artifact(javadocJar)

            pom {
                name.set("NuProcess Shim")
                description.set("Provides the nuprocess API on top of the Java Process API")
                url.set("https://github.com/v47-io/nuprocess-shim")

                licenses {
                    license {
                        name.set("BSD 3-Clause Clear License")
                        url.set("https://spdx.org/licenses/BSD-3-Clause-Clear.html")
                    }
                }

                developers {
                    developer {
                        id.set("vemilyus")
                        name.set("Alex Katlein")
                        email.set("dev@vemilyus.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/v47-io/nuprocess-shim.git")
                    developerConnection.set("scm:git:git://github.com/v47-io/nuprocess-shim.git")
                    url.set("https://github.com/v47-io/nuprocess-shim")
                }
            }
        }
    }
}

val ossrhUser: String? = project.findProperty("ossrhUser") as? String ?: System.getenv("OSSRH_USER")
val ossrhPass: String? = project.findProperty("osshrPass") as? String ?: System.getenv("OSSRH_PASS")

if (!ossrhUser.isNullOrBlank() && !ossrhPass.isNullOrBlank() && !"${project.version}".endsWith("-SNAPSHOT")) {
    applyPlugin("signing")
    applyPlugin("name.remal.maven-publish-ossrh")

    publishing {
        repositories {
            @Suppress("DEPRECATION")
            withConvention(RepositoryHandlerOssrhExtension::class) {
                ossrh {
                    credentials {
                        username = ossrhUser
                        password = ossrhPass
                    }
                }
            }
        }
    }
}
