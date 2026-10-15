plugins {
    // Lets Gradle download the JDK named in the toolchain (Java 25) if it isn't installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "prismo"
