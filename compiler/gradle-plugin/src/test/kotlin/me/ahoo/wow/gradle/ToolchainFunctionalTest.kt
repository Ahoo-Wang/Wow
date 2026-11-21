/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.gradle

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A module whose Java toolchain is newer than the JDK that runs Gradle (issue 6508), built with the plugins (Gradle
 * TestKit): Gradle runs on one JDK (`wow-test.jdk.daemon`, the build's own toolchain, 21) and the three modules of a
 * context, an api, a domain and a service, compile for another (`wow-test.jdk.module`, 25), so the classes the tasks read
 * have a class file version the daemon's JVM cannot load. Every task of the plugin runs: the one that loads the
 * module's classes ([GenerateManifestFragment]) runs on the module's JVM, and the others, which read Manifests and
 * contracts as files, in the daemon's.
 *
 * The test needs the two JDKs, which the build finds through its toolchains (`compiler/gradle-plugin/build.gradle.kts`):
 * the second is resolved like the JDK 25 of `-Pwow.test.java=25`, found on the machine or downloaded by the toolchain
 * resolver, and a machine that has neither (offline, with only the one JDK) skips the test, which CI never does.
 */
class ToolchainFunctionalTest {
    private fun jdk(property: String): File? = System.getProperty(property)?.let(::File)

    /** Writes the fixture: Gradle on [daemon], every module on [module]'s toolchain. */
    private fun build(root: File, daemon: File, module: File, moduleVersion: Int) {
        ContextsFixture.write(
            root,
            "settings.gradle.kts",
            """
            rootProject.name = "contexts"
            include("a-api", "a-domain", "a-server")
            """,
        )
        // Both JDKs are all the toolchains of the fixture's build can find, so it never downloads or detects another.
        ContextsFixture.write(
            root,
            "gradle.properties",
            """
            kotlin.compiler.execution.strategy=in-process
            org.gradle.jvmargs=-Xmx1g
            org.gradle.java.home=${daemon.invariantSeparatorsPath}
            org.gradle.java.installations.auto-detect=false
            org.gradle.java.installations.auto-download=false
            org.gradle.java.installations.paths=${daemon.invariantSeparatorsPath},${module.invariantSeparatorsPath}
            """,
        )
        ContextsFixture.write(
            root,
            "build.gradle.kts",
            """
            tasks.register("gradleJava") { doLast { println("gradle runs on JDK " + JavaVersion.current().majorVersion) } }
            """,
        )
        ContextsFixture.context(
            root,
            ContextsFixture.Context(
                "a",
                "Alpha",
                "b",
                "Beta",
                consumes = false,
                extra = "kotlin { jvmToolchain($moduleVersion) }",
            ),
        )
    }

    private fun runner(root: File, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root)
        .withPluginClasspath()
        .withArguments(*tasks, "--configuration-cache", "--stacktrace")
        .forwardOutput()

    /** The major version of a class file: what the JVM that loads it must be at least. */
    private fun classFileVersion(file: File): Int = file.inputStream().use { input ->
        val header = ByteArray(8).also { input.readNBytes(it, 0, it.size) }
        (header[6].toInt() and 0xFF shl 8) or (header[7].toInt() and 0xFF)
    }

    @Test
    fun `every task should build a module whose toolchain is newer than the JDK that runs Gradle`(@TempDir root: File) {
        val daemon = jdk("wow-test.jdk.daemon")
        val module = jdk("wow-test.jdk.module")
        // A CI run has both JDKs: a missing one there is a fault of the run, never a reason to skip.
        check(daemon != null && module != null || System.getenv("CI").isNullOrEmpty()) {
            "CI has no second JDK for the toolchain test: daemon=$daemon, module=$module"
        }
        assumeTrue(daemon != null && module != null, "The build found no second JDK to compile the module with")
        val moduleVersion = checkNotNull(System.getProperty("wow-test.jdk.module.version")).toInt()
        build(root, checkNotNull(daemon), checkNotNull(module), moduleVersion)

        val tasks = arrayOf(
            ":gradleJava",
            ":a-domain:${WowPlugin.GENERATE_FRAGMENT_TASK}",
            ":a-domain:${WowPlugin.GENERATE_CONTRACT_TASK}",
            ":a-api:${WowPlugin.ASSEMBLE_CONTRACT_TASK}",
            ":a-api:${WowPlugin.GENERATE_CONTRACT_SOURCES_TASK}",
            ":a-api:${WowPlugin.CONTRACT_JAR_TASK}",
            ":a-server:${WowPlugin.MERGE_TASK}",
            ":a-server:${WowPlugin.OPENAPI_TASK}",
            ":a-server:${WowPlugin.HELM_VALUES_TASK}",
            ":a-server:${WowPlugin.UPDATE_BASELINE_TASK}",
        )
        val first = runner(root, *tasks).build()
        // The precondition: Gradle on the older JDK, the classes the tasks read compiled for the newer one.
        first.output.assert().contains("gradle runs on JDK ${jdkMajor(daemon)}")
        classFileVersion(File(root, "a-domain/build/classes/kotlin/main/a/domain/AlphaState.class")).assert()
            .isEqualTo(moduleVersion + CLASS_FILE_OFFSET)
        tasks.drop(1).forEach { first.task(it)?.outcome.assert().describedAs(it).isEqualTo(TaskOutcome.SUCCESS) }
        // The fragment was completed with the schemas of the module's classes, which only a JVM that reads them gives.
        ManifestJson.read(File(root, "a-server/build/wow/manifest.json")).defs.keys
            .any { it.endsWith("OpenAlpha") }.assert().isTrue()

        // Nothing reruns, and the configuration cache is reused: the toolchain is an input, not a reason to rerun.
        val checked = arrayOf(":a-server:${WowPlugin.CHECK_TASK}", ":a-domain:${WowPlugin.GENERATE_FRAGMENT_TASK}")
        val again = runner(root, *checked).build()
        again.output.assert().contains("Configuration cache entry")
        again.task(checked.first())?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        again.task(checked.last())?.outcome.assert().isEqualTo(TaskOutcome.UP_TO_DATE)
        val reused = runner(root, *checked).build()
        reused.output.assert().contains("Configuration cache entry reused")
        checked.forEach { reused.task(it)?.outcome.assert().describedAs(it).isEqualTo(TaskOutcome.UP_TO_DATE) }
    }

    private companion object {
        /** A class file's major version is its Java version plus 44. */
        const val CLASS_FILE_OFFSET = 44

        /** The Java version a JDK's `release` file names. */
        fun jdkMajor(home: File): String = File(home, "release").readLines()
            .first { it.startsWith("JAVA_VERSION=") }
            .substringAfter('"').substringBefore('"').substringBefore('.')
    }
}
