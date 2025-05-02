import com.vanniktech.maven.publish.SonatypeHost
import java.net.URI
import java.util.*
import java.util.Calendar.YEAR

plugins {
    kotlin("jvm")

    alias(libs.plugins.detekt)
    jacoco

    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.license)
    alias(libs.plugins.release)

    alias(libs.plugins.nmcp.maven.publish)
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

license {
    exclude("**/*.exe")

    header = file("LICENSE")
    skipExistingHeaders = true

    ext {
        set("year", Calendar.getInstance().get(YEAR))
    }
}

release {
    tagTemplate = "v\$version"
}


dokka {
    dokkaSourceSets.main {
        val revision = "${project.version}".let { version ->
            if (version.endsWith("-SNAPSHOT"))
                "main"
            else
                "v$version"
        }

        sourceLink {
            localDirectory.set(file("src/main/kotlin"))

            remoteUrl = URI("https://github.com/v47-io/nuprocess-shim/blob/$revision/src/main/kotlin")
            remoteLineSuffix = "#L"
        }

        sourceLink {
            localDirectory.set(file("src/main/java"))

            remoteUrl = URI("https://github.com/v47-io/nuprocess-shim/blob/$revision/src/main/java")
            remoteLineSuffix = "#L"
        }
    }

    pluginsConfiguration {
        val copyright = "Copyright (c) ${Calendar.getInstance().get(YEAR)} Alex Katlein"

        html {
            footerMessage = copyright
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates("${project.group}", project.name, "${project.version}")

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
