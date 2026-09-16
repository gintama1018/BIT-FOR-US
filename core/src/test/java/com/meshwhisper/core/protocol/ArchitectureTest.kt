package com.meshwhisper.core.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Architecture and invariants tests:
 * - T-ARCH-01: No file under router/ or media/ references PureCryptoEngine, verifySignature, or IdentityStore.
 * - T-ARCH-02: AuthenticatedPacket has no public constructor and no public factory.
 * - T-ARCH-03: Exactly one call site constructs AuthenticatedPacket (in PacketPipeline.kt).
 */
class ArchitectureTest {

    private fun findRootDir(): File {
        val current = File(".").canonicalFile
        if (File(current, "app").exists() && File(current, "core").exists()) {
            return current
        }
        if (current.parentFile != null && File(current.parentFile, "app").exists()) {
            return current.parentFile
        }
        return current
    }

    @Test
    fun testTArch01RouterAndMediaDecoupledFromDirectCryptoAndIdentityStore() {
        val rootDir = findRootDir()
        val dirsToScan = listOf(
            File(rootDir, "app/src/main/java/com/meshwhisper/app/router"),
            File(rootDir, "app/src/main/java/com/meshwhisper/app/media"),
            File(rootDir, "desktop/src/main/java/com/meshwhisper/desktop/router"),
            File(rootDir, "desktop/src/main/java/com/meshwhisper/desktop/media")
        )

        val forbiddenTokens = listOf("PureCryptoEngine", "verifySignature", "IdentityStore")
        val violations = mutableListOf<String>()

        for (dir in dirsToScan) {
            assertThat(dir.exists()).isTrue()
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val content = file.readText()
                for (token in forbiddenTokens) {
                    if (content.contains(token)) {
                        violations.add("${file.relativeTo(rootDir).path} contains forbidden token '$token'")
                    }
                }
            }
        }

        assertThat(violations).isEmpty()
    }

    @Test
    fun testTArch02AuthenticatedPacketHasNoPublicConstructorOrPublicFactory() {
        val clazz = AuthenticatedPacket::class.java

        // 1. Zero non-synthetic public constructors
        val publicCtors = clazz.constructors.filter {
            !it.isSynthetic && !it.parameterTypes.any { pt -> pt.name.contains("DefaultConstructorMarker") }
        }
        assertThat(publicCtors).isEmpty()

        // 2. The primary declared constructor must be private
        val primaryCtor = clazz.declaredConstructors.firstOrNull {
            !it.isSynthetic && !it.parameterTypes.any { pt -> pt.name.contains("DefaultConstructorMarker") }
        }
        assertThat(primaryCtor).isNotNull()
        assertThat(Modifier.isPrivate(primaryCtor!!.modifiers)).isTrue()

        // 3. No public factory methods on the class or companion that are accessible as public Java API
        // (Internal Kotlin methods are compiled with $core suffix or internal visibility)
        for (method in clazz.declaredMethods) {
            if (method.returnType == clazz && !method.name.contains("$")) {
                assertThat(Modifier.isPublic(method.modifiers)).isFalse()
            }
        }

        val companionField = clazz.declaredFields.find { it.name == "Companion" }
        if (companionField != null) {
            val companionClass = companionField.type
            for (method in companionClass.declaredMethods) {
                if (method.returnType == clazz && !method.name.contains("$")) {
                    assertThat(Modifier.isPublic(method.modifiers)).isFalse()
                }
            }
        }
    }

    @Test
    fun testTArch03ExactlyOneCallSiteConstructsAuthenticatedPacket() {
        val rootDir = findRootDir()
        val searchDirs = listOf(
            File(rootDir, "core/src/main/java"),
            File(rootDir, "app/src/main/java"),
            File(rootDir, "desktop/src/main/java")
        )

        val callSites = mutableListOf<String>()
        val targetSymbol = "AuthenticatedPacket.createFromPipeline"

        for (dir in searchDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { idx, line ->
                    // Exclude the definition in AuthenticatedPacket.kt itself
                    if (line.contains(targetSymbol) && !file.name.contains("AuthenticatedPacket")) {
                        callSites.add("${file.relativeTo(rootDir).path}:${idx + 1}")
                    }
                }
            }
        }

        // Must have exactly ONE call site in production code: PacketPipeline.kt
        assertThat(callSites).hasSize(1)
        assertThat(callSites[0]).contains("PacketPipeline.kt")
    }
}
