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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

/**
 * Modules are named by their project paths (decision D363 ②, finding G1): two domain modules both named `domain`, of
 * the contexts `orders` and `billing`, keep a fragment each, and the service merges both contexts under the name of
 * its own path. Named by `project.name`, both fragments were `domain.json`, and the node read one of them alone.
 */
class ModuleNamesFunctionalTest {
    private fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    private fun write(root: File, path: String, content: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    private fun module(root: File, path: String, dependencies: String = "") {
        write(
            root,
            "$path/build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("me.ahoo.wow")
            }

            repositories { mavenCentral() }

            dependencies {
                implementation(files(${classpath("wow.fixture.compileClasspath")}))
                ksp(files(${classpath("wow.fixture.processorClasspath")}))
                wowManifestTools(files(${classpath("wow.fixture.toolsClasspath")}))
                $dependencies
            }
            """,
        )
    }

    private fun context(root: File, path: String, context: String) {
        module(root, path)
        write(
            root,
            "$path/src/main/kotlin/$context/Ledger.kt",
            """
            package $context

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext
            import me.ahoo.wow.api.annotation.CreateAggregate

            @BoundedContext(name = "$context")
            object Context

            @CreateAggregate
            data class Open(val name: String)
            data class Opened(val name: String)

            @AggregateRoot
            class Ledger(private val state: LedgerState) {
                fun onCommand(command: Open): Opened = Opened(command.name)
            }

            class LedgerState(val id: String) {
                var name: String? = null

                fun onSourcing(event: Opened) { name = event.name }
            }
            """,
        )
    }

    @Test
    fun `two modules of one name should keep a fragment each, and the service both contexts`(@TempDir root: File) {
        write(
            root,
            "settings.gradle.kts",
            """rootProject.name = "shop"; include(":orders:domain", ":billing:domain", ":app:server")""",
        )
        write(root, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g")
        context(root, "orders/domain", "orders")
        context(root, "billing/domain", "billing")
        module(
            root,
            "app/server",
            dependencies = """
                implementation(project(":orders:domain"))
                implementation(project(":billing:domain"))
            """,
        )
        GradleRunner.create().withProjectDir(root).withPluginClasspath().forwardOutput()
            .withArguments(":app:server:${WowPlugin.MERGE_TASK}", ":orders:domain:jar", ":billing:domain:jar")
            .build()
        val manifest = ManifestJson.read(File(root, "app/server/build/wow/manifest.json"))
        manifest.contexts.map { it.name }.assert().containsExactly("billing", "orders")
        manifest.service.assert().isEqualTo(ServiceInfo("app-server", "unspecified"))
        mapOf("orders" to "orders-domain", "billing" to "billing-domain").forEach { (context, module) ->
            ZipFile(File(root, "$context/domain/build/libs/domain.jar")).use { jar ->
                jar.getEntry("${Manifest.FRAGMENT_DIRECTORY}/$module.json").assert().isNotNull()
            }
        }
    }
}
