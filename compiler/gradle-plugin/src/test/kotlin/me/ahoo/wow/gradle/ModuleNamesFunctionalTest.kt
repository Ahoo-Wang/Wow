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
 * its own path. The service module has no model of its own, so it contributes no fragment (D363 ①). Named by `project.name`, both fragments were `domain.json`, and the node read one of them alone.
 * A fragment's name is its path as directories (#5496): `:orders:domain` is `orders/domain.json`, apart from
 * `:orders-domain`'s `orders-domain.json`, which a path joined by `-` gave both.
 */
class ModuleNamesFunctionalTest {
    private fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    private fun write(root: File, path: String, content: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    private fun module(root: File, path: String, dependencies: String = "", id: String = WowPlugin.ID) {
        write(
            root,
            "$path/build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("$id")
            }

            repositories {
                mavenCentral()
                maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }
            }

            dependencies {
                implementation(files(${classpath("wow-test.fixture.compileClasspath")}))
                ksp(files(${classpath("wow-test.fixture.processorClasspath")}))
                wowTools(files(${classpath("wow-test.fixture.toolsClasspath")}))
                $dependencies
            }
            """,
        )
    }

    /** A domain module at [path] of the bounded [context], its classes in the package [pkg]. */
    private fun context(root: File, path: String, context: String, pkg: String = context) {
        module(root, path)
        write(
            root,
            "$path/src/main/kotlin/$pkg/Ledger.kt",
            """
            package $pkg

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
            """rootProject.name = "shop"; include(":orders:domain", ":billing:domain", ":orders-domain", ":app:server")""",
        )
        write(root, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g")
        context(root, "orders/domain", "orders")
        context(root, "billing/domain", "billing")
        context(root, "orders-domain", "refunds")
        module(
            root,
            "app/server",
            id = WowPlugin.SERVICE_ID,
            dependencies = """
                implementation(project(":orders:domain"))
                implementation(project(":billing:domain"))
                implementation(project(":orders-domain"))
            """,
        )
        GradleRunner.create().withProjectDir(root).withPluginClasspath().forwardOutput()
            .withArguments(
                ":app:server:${WowPlugin.MERGE_TASK}",
                ":app:server:${WowPlugin.GENERATE_CONTRACT_TASK}",
                ":orders:domain:jar",
                ":billing:domain:jar",
                ":orders-domain:jar",
            )
            .build()
        val manifest = ManifestJson.read(File(root, "app/server/build/wow/manifest.json"))
        manifest.contexts.map { it.name }.assert().containsExactly("billing", "orders", "refunds")
        manifest.service.assert().isEqualTo(ServiceInfo("app-server", "unspecified"))
        // The server has no model or processor of its own: no fragment, and an empty contract.
        File(root, "app/server/build/wow/manifest-fragment/${Manifest.FRAGMENT_DIRECTORY}").exists().assert().isFalse()
        ManifestJson.read(File(root, "app/server/build/wow/contract.json")).contexts.assert().isEmpty()
        mapOf(
            "orders/domain/build/libs/domain.jar" to "orders/domain",
            "billing/domain/build/libs/domain.jar" to "billing/domain",
            "orders-domain/build/libs/orders-domain.jar" to "orders-domain",
        ).forEach { (jarPath, module) ->
            ZipFile(File(root, jarPath)).use { jar ->
                jar.getEntry("${Manifest.FRAGMENT_DIRECTORY}/$module.json").assert().isNotNull()
            }
        }
    }

    @Test
    fun `two modules whose providers share a name and a package should be refused, naming both`(@TempDir root: File) {
        write(
            root,
            "settings.gradle.kts",
            """rootProject.name = "shop"; include(":orders:domain", ":orders-domain", ":server")"""
        )
        write(root, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g")
        // Both keep their models in `orders`: both generate `orders.OrdersDomainModelProvider`.
        context(root, "orders/domain", "orders")
        context(root, "orders-domain", "refunds", pkg = "orders")
        module(
            root,
            "server",
            id = WowPlugin.SERVICE_ID,
            dependencies = """
                implementation(project(":orders:domain"))
                implementation(project(":orders-domain"))
            """,
        )
        val result = GradleRunner.create().withProjectDir(root).withPluginClasspath().forwardOutput()
            .withArguments(":server:${WowPlugin.MERGE_TASK}")
            .buildAndFail()
        result.output.assert().contains(
            "The model provider orders.OrdersDomainModelProvider is registered by project ':orders:domain' and " +
                "project ':orders-domain'",
        )
    }
}
