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
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The staged migration of a 9.x build, with Gradle TestKit: its api module moves to `me.ahoo.wow.api` first, while its
 * other modules still apply KSP of their own version, and the root build puts the Wow plugin on its class path for
 * them all (`apply false`). The class path is the one the api plugin's marker resolves ([API_CLASSPATH]): the plugin's
 * jar and its dependencies, beside the Kotlin Gradle plugin the root build declares. A domain module that applies
 * `me.ahoo.wow` from that class path finds no KSP there, and fails naming the line to add.
 */
class StagedMigrationFunctionalTest {
    private val kspVersion: String = requireNotNull(System.getProperty(KSP_VERSION)) { "$KSP_VERSION is not set." }

    private fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    private fun write(root: File, path: String, content: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    /**
     * The staged build: the root puts the Kotlin plugin and `me.ahoo.wow.api` on the class path, `legacy` is a 9.x
     * module on KSP [kspVersion] of its own, and `api` applies the api plugin.
     */
    private fun staged(root: File, vararg modules: String) {
        write(
            root,
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            rootProject.name = "staged"
            include(${(listOf("legacy", "api") + modules).joinToString { "\"$it\"" }})
            """,
        )
        write(root, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g")
        write(
            root,
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm") apply false
                id("${WowPlugin.API_ID}") apply false
            }
            """,
        )
        write(
            root,
            "legacy/build.gradle.kts",
            """
            // A 9.x module: it applies KSP of its own version, and runs the 9.x compiler on it.
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("com.google.devtools.ksp") version "$kspVersion"
            }

            repositories { mavenCentral() }
            """,
        )
        write(root, "legacy/src/main/kotlin/legacy/Legacy.kt", "package legacy\n\nclass Legacy\n")
        api(root)
    }

    /** The api module `api`, applying `me.ahoo.wow.api` and declaring no domain module yet. */
    private fun api(root: File) {
        write(
            root,
            "api/build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("${WowPlugin.API_ID}")
            }

            repositories {
                mavenCentral()
                maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }
            }

            dependencies {
                implementation(files(${classpath("wow-test.fixture.compileClasspath")}))
                // The Wow tools as files, as the other fixtures take them: a version not yet on Maven Central resolves.
                wowTools(files(${classpath("wow-test.fixture.toolsClasspath")}))
            }
            """,
        )
        write(
            root,
            "api/src/main/kotlin/bank/api/OpenAccount.kt",
            "package bank.api\n\ndata class OpenAccount(val owner: String)\n",
        )
    }

    /** A domain module `domain` of the bank, applying `me.ahoo.wow` and the plugins [plugins]. */
    private fun domain(root: File, plugins: String = "") {
        write(
            root,
            "domain/build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("${WowPlugin.ID}")
                $plugins
            }

            repositories {
                mavenCentral()
                maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }
            }

            dependencies {
                implementation(files(${classpath("wow-test.fixture.compileClasspath")}))
                ksp(files(${classpath("wow-test.fixture.processorClasspath")}))
                wowTools(files(${classpath("wow-test.fixture.toolsClasspath")}))
            }
            """,
        )
        write(
            root,
            "domain/src/main/kotlin/bank/Account.kt",
            """
            package bank

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext
            import me.ahoo.wow.api.annotation.CreateAggregate

            @BoundedContext(name = "bank")
            object Bank

            @CreateAggregate
            data class OpenAccount(val owner: String)
            data class AccountOpened(val owner: String)

            @AggregateRoot
            class Account(private val state: AccountState) {
                fun onCommand(command: OpenAccount): AccountOpened = AccountOpened(command.owner)
            }

            class AccountState(val id: String) {
                var owner: String = ""
                    private set

                fun onSourcing(event: AccountOpened) { owner = event.owner }
            }
            """,
        )
    }

    private fun runner(root: File, vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root)
        .withPluginClasspath(System.getProperty(API_CLASSPATH).split(File.pathSeparator).map(::File))
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    @Test
    fun `a 9 x module on KSP of its own version should build beside an api module the root build applies the plugin for`(
        @TempDir root: File
    ) {
        staged(root)
        val result = runner(root, ":legacy:compileKotlin", ":api:jar").build()
        result.task(":legacy:compileKotlin")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        result.task(":api:jar")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun `a domain module applying the plugin from the root's class path should fail naming the KSP line to add`(
        @TempDir root: File
    ) {
        staged(root, "domain")
        domain(root)
        val result = runner(root, ":domain:help").buildAndFail()
        result.output.assert().contains(
            "Project ':domain' applies the Wow plugin (${WowPlugin.ID}) from a class path without the KSP Gradle plugin",
            "Add id(\"com.google.devtools.ksp\") version \"$kspVersion\" to the module's plugins",
        )
    }

    @Test
    fun `a domain module applying KSP itself should process its models beside the 9 x module`(@TempDir root: File) {
        staged(root, "domain")
        domain(root, "id(\"com.google.devtools.ksp\") version \"$kspVersion\"")
        val result = runner(root, ":legacy:compileKotlin", ":domain:${WowPlugin.GENERATE_FRAGMENT_TASK}").build()
        result.task(":domain:kspKotlin")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        File(root, "domain/build/wow/manifest-fragment/META-INF/wow/manifest/domain.json").isFile.assert().isTrue()
    }

    /** The dependencies of the plugin marker publication [name], as `group:artifact:version`. */
    private fun markerDependencies(name: String): List<String> {
        val pom = File(System.getProperty(MARKERS), "$name/pom-default.xml")
        val dependencies = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
            .getElementsByTagName("dependency")
        return (0 until dependencies.length).map { index ->
            val dependency = dependencies.item(index) as org.w3c.dom.Element
            listOf("groupId", "artifactId", "version")
                .joinToString(":") { dependency.getElementsByTagName(it).item(0).textContent }
        }
    }

    @Test
    fun `the markers should bring KSP for the plugins whose role applies it alone`() {
        val plugin = "me.ahoo.wow:wow-gradle-plugin:${WowPlugin.version}"
        val ksp = "com.google.devtools.ksp:symbol-processing-gradle-plugin:$kspVersion"
        markerDependencies("wowApiPluginMarkerMaven").assert().containsExactly(plugin)
        markerDependencies("wowPluginMarkerMaven").assert().containsExactly(plugin, ksp)
        markerDependencies("wowServicePluginMarkerMaven").assert().containsExactly(plugin, ksp)
        WowPlugin.kspVersion.assert().isEqualTo(kspVersion)
    }

    private companion object {
        /** The directory of the plugin's publications, where the markers' POMs are generated. */
        const val MARKERS = "wow-test.plugin.markers"

        /** The class path `me.ahoo.wow.api`'s marker resolves, with the Kotlin Gradle plugin. */
        const val API_CLASSPATH = "wow-test.plugin.apiClasspath"

        /** The version of KSP the plugin brings to the modules of the domain role. */
        const val KSP_VERSION = "wow-test.ksp.version"
    }
}
