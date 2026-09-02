plugins {
    id("org.jetbrains.kotlin.jvm")
    id("application")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.meshwhisper.desktop.MainKt")
}

dependencies {
    // Shared Core Module
    implementation(project(":core"))

    // Embedded SQLite Database for Desktop
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.register<Exec>("packageWindowsExe") {
    dependsOn("installDist")
    group = "distribution"
    description = "Packages a standalone Windows MeshWhisper.exe application bundle via jpackage"

    val jdkBin = org.gradle.internal.jvm.Jvm.current().javaHome.resolve("bin")
    val jpackageExe = jdkBin.resolve("jpackage.exe").absolutePath

    val distDir = layout.buildDirectory.dir("distributions").get().asFile
    val installLibDir = layout.buildDirectory.dir("install/desktop/lib").get().asFile

    doFirst {
        val targetAppDir = distDir.resolve("MeshWhisper")
        if (targetAppDir.exists()) {
            targetAppDir.walkBottomUp().forEach { file ->
                file.setWritable(true)
                file.delete()
            }
            targetAppDir.delete()
        }
    }

    commandLine(
        jpackageExe,
        "--type", "app-image",
        "--dest", distDir.absolutePath,
        "--name", "MeshWhisper",
        "--input", installLibDir.absolutePath,
        "--main-jar", "desktop.jar",
        "--main-class", "com.meshwhisper.desktop.MainKt",
        "--win-console"
    )
}
