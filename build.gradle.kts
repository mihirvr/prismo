plugins {
    application
    alias(libs.plugins.javafx)
}

group = "io.github.mihirvr"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

repositories {
    mavenCentral()
}

// Puts the JavaFX jars for this OS on the module path for `run` and `test`.
// The app itself stays on the classpath (no module-info.java, see TECH_STACK.md).
// Known: plugin 0.1.0 triggers Gradle's "Task.extensions at execution time" deprecation on `run`.
// Fine on Gradle 9; before moving to Gradle 10, update the plugin or replace it with plain config.
javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls")
}

// JavaCPP's name for this OS, which picks the jar with FFmpeg's native files (DLLs / .so) for it.
// The installer (#10) is built on each OS, so it bundles only its own.
val nativePlatform =
    if (System.getProperty("os.name").lowercase().startsWith("windows")) "windows-x86_64" else "linux-x86_64"

dependencies {
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
    implementation(libs.sqlite.jdbc)
    implementation(libs.metadata.extractor)
    runtimeOnly(libs.twelvemonkeys.jpeg)
    runtimeOnly(libs.twelvemonkeys.webp)
    // Only the FFmpeg part of JavaCV: its pom also pulls in OpenCV, Tesseract, camera drivers and more.
    implementation(libs.javacv) { isTransitive = false }
    implementation(libs.javacpp)
    implementation(libs.ffmpeg)
    runtimeOnly(variantOf(libs.javacpp) { classifier(nativePlatform) })
    runtimeOnly(variantOf(libs.ffmpeg) { classifier(nativePlatform) })

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// javafx.graphics = JavaFX's native rendering; ALL-UNNAMED = classpath jars: sqlite-jdbc's native SQLite
// and JavaCPP loading FFmpeg (the video player and video thumbnails).
val nativeAccess = "--enable-native-access=javafx.graphics,ALL-UNNAMED"

application {
    mainClass = "io.github.mihirvr.prismo.Launcher"
    // Since JDK 24 the JVM warns when code loads native libraries unless it is allowed up front.
    // JavaFX loads its native graphics code; the installer launcher (#10) needs the same flag.
    applicationDefaultJvmArgs = listOf(nativeAccess)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // JavaCPP unpacks FFmpeg's native files here once (about 76 MB on Windows), not into ~/.javacpp.
    systemProperty("org.bytedeco.javacpp.cachedir", layout.buildDirectory.dir("javacpp-cache").get().asFile.path)
}
