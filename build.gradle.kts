import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest

plugins {
    application
    alias(libs.plugins.javafx)
}

group = "io.github.mihirvr"
version = "1.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
        // Eclipse Temurin (OpenJDK, GPLv2 + Classpath Exception): the installer ships a runtime made from it,
        // so it must be free to redistribute. Downloaded by the foojay plugin if it isn't installed.
        vendor = JvmVendorSpec.ADOPTIUM
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

// Settings › About shows the version: written into app.properties when the resources are copied.
tasks.processResources {
    val appVersion = project.version.toString()
    inputs.property("appVersion", appVersion)
    filesMatching("app.properties") {
        expand("version" to appVersion)
    }
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

// ---------- Packaging (#10): jlink runtime, jpackage app folder, installer ----------
// The app stays on the classpath (no module-info.java, see TECH_STACK.md "Modules and packaging"):
// jlink builds only the Java runtime (JDK modules + JavaFX), jpackage puts our jars on top.
// Each OS builds its own installer (jpackage can't cross-build): .exe on Windows, .deb on Linux.
//   ./gradlew appImage    the ready-to-run folder build/packaging/image/Prismo (no installer tool needed)
//   ./gradlew installer   the installer in build/packaging/installer (Windows needs WiX 4 or 5, see DEVELOPMENT.md)

val isWindows = nativePlatform.startsWith("windows")

// JDK modules the jars use: `jdeps --print-module-deps --ignore-missing-deps` on every jar in the app
// (for the modular jars javacv, javacpp, ffmpeg and logback-classic: on their classes). Re-run it when a
// library is added (DEVELOPMENT.md, "The Dependency Rule"). javafx.controls brings javafx.graphics and javafx.base.
val runtimeModules = listOf(
    "java.base", "java.desktop", "java.logging", "java.management", "java.naming", "java.sql", "java.xml",
    "jdk.unsupported", "javafx.controls",
)

// JavaFX's jmods (jlink needs them; Maven Central has only jars), from Gluon, checked against the
// SHA-256 Gluon publishes next to each zip.
val javafxVersion = libs.versions.javafx.get()
val javafxJmodsOs = if (isWindows) "windows-x64" else "linux-x64"
val javafxJmodsSha256 = mapOf(
    "windows-x64" to "c5471e71d3f66db340c1e59d8b4924487ab4917aaf641fc1e4e0fa1a174f79e1",
    "linux-x64" to "3816e607a183a9d949efab45b4f1f68d08042a72cfd2f1c0e868fc6dc6d55573",
)
val packagingDir = layout.buildDirectory.dir("packaging")

// The version shown by the installer and in Windows' installed-apps list: numbers only (jpackage refuses "-SNAPSHOT").
val packageVersion = project.version.toString().substringBefore("-")

// Fixed forever: lets a newer Prismo installer replace an older one instead of installing next to it.
val windowsUpgradeUuid = "39ba06ee-c306-4f35-bdb6-28f103dc7eaf"

val vendor = "Mihir Revaskar"
val copyrightLine = "Copyright (c) 2026 Mihir Revaskar"
val summary = "A phone-style photo and video gallery for your PC"

val toolchainHome = javaToolchains.compilerFor(java.toolchain).map { it.metadata.installationPath.asFile }
fun jdkTool(name: String) = toolchainHome.map { it.resolve("bin").resolve(if (isWindows) "$name.exe" else name).path }

val downloadJavafxJmods = tasks.register("downloadJavafxJmods") {
    description = "Downloads JavaFX's jmods for this OS and checks their SHA-256."
    val zip = packagingDir.map { it.file("openjfx-$javafxVersion-$javafxJmodsOs-jmods.zip") }
    val expected = javafxJmodsSha256.getValue(javafxJmodsOs)
    inputs.property("sha256", expected)
    outputs.file(zip)
    doLast {
        val file = zip.get().asFile
        file.parentFile.mkdirs()
        val url = "https://download2.gluonhq.com/openjfx/$javafxVersion/openjfx-${javafxVersion}_${javafxJmodsOs}_bin-jmods.zip"
        URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw GradleException("JavaFX jmods from $url have SHA-256 $actual, expected $expected")
        }
    }
}

val javafxJmods = tasks.register<Sync>("javafxJmods") {
    description = "Unpacks JavaFX's jmods."
    from(downloadJavafxJmods.map { zipTree(it.outputs.files.singleFile) })
    into(packagingDir.map { it.dir("javafx-jmods") })
}

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    group = "distribution"
    description = "Builds the Java runtime the installer ships: the JDK modules Prismo uses plus JavaFX."
    dependsOn(javafxJmods)
    val output = packagingDir.map { it.dir("runtime") }
    inputs.property("modules", runtimeModules)
    inputs.property("jdk", toolchainHome.map { it.path })
    outputs.dir(output)
    doFirst {
        delete(output) // jlink refuses to write into an existing folder
        executable = jdkTool("jlink").get()
    }
    // Temurin 25 has no jmods folder (JEP 493): jlink takes the JDK modules from the JDK itself
    // and only JavaFX from the module path.
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--module-path", packagingDir.get().dir("javafx-jmods/javafx-jmods-$javafxVersion").asFile.path,
            "--add-modules", runtimeModules.joinToString(","),
            "--strip-debug", "--no-header-files", "--no-man-pages", "--compress", "zip-6",
            "--output", output.get().asFile.path,
        )
    })
}

// Our jar and the libraries, minus JavaFX's jars: JavaFX comes from the runtime as real modules.
val packageInput = tasks.register<Sync>("packageInput") {
    from(tasks.jar)
    from(configurations.runtimeClasspath) { exclude("javafx-*") }
    into(packagingDir.map { it.dir("input") })
}

// Windows wants one .ico holding several sizes; it is made from the app icon PNGs (PNG-compressed entries).
val windowsIcon = tasks.register("windowsIcon") {
    val sizes = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)
    val pngs = sizes.map { layout.projectDirectory.file("src/main/resources/ui/icon/prismo-$it.png") }
    val ico = packagingDir.map { it.file("prismo.ico") }
    inputs.files(pngs)
    outputs.file(ico)
    doLast {
        val images = pngs.map { it.asFile.readBytes() }
        val out = ByteArrayOutputStream()
        fun short(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        fun int(v: Int) { short(v and 0xffff); short((v ushr 16) and 0xffff) }
        short(0); short(1); short(images.size) // header: reserved, type 1 = icon, image count
        var offset = 6 + 16 * images.size
        images.forEachIndexed { i, png ->
            val size = sizes[i] % 256 // 0 means 256
            out.write(size); out.write(size); out.write(0); out.write(0) // width, height, no palette, reserved
            short(1); short(32); int(png.size); int(offset) // planes, bits per pixel, bytes, where it starts
            offset += png.size
        }
        images.forEach { out.write(it) }
        ico.get().asFile.writeBytes(out.toByteArray())
    }
}

val appImage = tasks.register<Exec>("appImage") {
    group = "distribution"
    description = "Builds the ready-to-run Prismo folder (runtime + jars + launcher) in build/packaging/image."
    dependsOn(jlinkRuntime, packageInput, windowsIcon)
    val dest = packagingDir.map { it.dir("image") }
    val notices = layout.projectDirectory.dir("src/packaging/notices")
    inputs.dir(packagingDir.map { it.dir("runtime") })
    inputs.dir(packagingDir.map { it.dir("input") })
    inputs.dir(notices)
    outputs.dir(dest)
    doFirst {
        delete(dest)
        executable = jdkTool("jpackage").get()
    }
    argumentProviders.add(CommandLineArgumentProvider {
        val dir = packagingDir.get()
        listOf(
            "--type", "app-image",
            "--name", "Prismo",
            "--app-version", packageVersion,
            "--vendor", vendor,
            "--copyright", copyrightLine,
            "--description", summary,
            "--icon", if (isWindows) dir.file("prismo.ico").asFile.path
                      else layout.projectDirectory.file("src/main/resources/ui/icon/prismo-256.png").asFile.path,
            "--input", dir.dir("input").asFile.path,
            "--main-jar", tasks.jar.get().archiveFileName.get(),
            "--main-class", application.mainClass.get(),
            "--runtime-image", dir.dir("runtime").asFile.path,
            "--java-options", nativeAccess,
            // THIRD_PARTY_NOTICES.md and the licence texts, next to the app's files.
            "--app-content", notices.asFile.path,
            "--dest", dest.get().asFile.path,
        )
    })
}

val installer = tasks.register<Exec>("installer") {
    group = "distribution"
    description = "Builds the installer for this OS (.exe on Windows, .deb on Linux) in build/packaging/installer."
    dependsOn(appImage)
    val dest = packagingDir.map { it.dir("installer") }
    inputs.dir(packagingDir.map { it.dir("image") })
    inputs.file(layout.projectDirectory.file("LICENSE"))
    outputs.dir(dest)
    doFirst {
        delete(dest)
        executable = jdkTool("jpackage").get()
    }
    argumentProviders.add(CommandLineArgumentProvider {
        val common = listOf(
            "--type", if (isWindows) "exe" else "deb",
            "--app-image", packagingDir.get().dir("image/Prismo").asFile.path,
            "--name", "Prismo",
            "--app-version", packageVersion,
            "--vendor", vendor,
            "--copyright", copyrightLine,
            "--description", summary,
            "--license-file", layout.projectDirectory.file("LICENSE").asFile.path,
            "--about-url", "https://prismo.mihirr.in",
            "--dest", dest.get().asFile.path,
        )
        val perOs = if (isWindows) listOf(
            "--win-per-user-install", // into the user's AppData: no admin (UAC) prompt
            // %LOCALAPPDATA%\Programs\Prismo, the usual place for per-user apps. Not jpackage's default
            // %LOCALAPPDATA%\Prismo: that is Prismo's data folder (database, thumbnails, logs; see AppPaths).
            "--install-dir", "Programs\\Prismo",
            "--win-dir-chooser",
            "--win-menu", "--win-menu-group", "Prismo",
            "--win-shortcut", "--win-shortcut-prompt", // the user picks whether to get a desktop shortcut
            "--win-upgrade-uuid", windowsUpgradeUuid,
        ) else listOf(
            "--linux-package-name", "prismo",
            "--linux-app-category", "graphics",
            "--linux-menu-group", "Graphics;Photography;",
            "--linux-shortcut",
            // Our own postinst / prerm (copies of jpackage's, see their header): adding Prismo to the menu can't
            // fail the install on a system without a desktop menu.
            "--resource-dir", layout.projectDirectory.dir("src/packaging/linux").asFile.path,
            // jpackage only sees what the files in the app folder link to. JavaFX loads GTK 3 at run time, and
            // FFmpeg's .so files sit inside a jar; their system libraries were read from those .so files
            // (sound: ALSA, PulseAudio; libudev; X11: xcb). Ubuntu 24.04 renamed some packages (the t64 names).
            "--linux-package-deps", listOf(
                "libgtk-3-0t64 | libgtk-3-0", "libasound2t64 | libasound2", "libpulse0", "libudev1",
                "libxcb1", "libxcb-shm0", "libstdc++6",
            ).joinToString(", "),
        )
        common + perOs
    })
}
