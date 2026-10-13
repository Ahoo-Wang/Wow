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
import me.ahoo.wow.manifest.PeerManifests
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

/**
 * Peer Manifests between services (decision D139), built with the plugin (Gradle TestKit):
 *
 * - `inventory` (context `inventory`) exposes its merged Manifest and publishes it to a file repository;
 * - `shop` (context `shop`) takes it as a peer, `wowPeers(project(":inventory"))`;
 * - `warehouse` declares the context `inventory` too and takes `inventory` as a peer, and `clash` takes both as
 *   peers: both builds fail;
 * - a separate build, `outside`, takes the published `inventory` as a peer by its coordinates.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WowPeersFunctionalTest {
    private lateinit var projectDir: File

    private lateinit var result: BuildResult

    private fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    private fun write(root: File, path: String, content: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    private fun module(root: File, name: String, plugins: String = "", extra: String = "", dependencies: String = "") {
        write(
            root,
            "$name/build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("me.ahoo.wow")
                $plugins
            }

            group = "sample"
            version = "1.0.0"

            repositories {
                mavenCentral()
                maven { url = uri("${File(projectDir, "repo").path.replace("\\", "/")}") }
            }

            dependencies {
                implementation(files(${classpath("wow.fixture.compileClasspath")}))
                ksp(files(${classpath("wow.fixture.processorClasspath")}))
                wowManifestTools(files(${classpath("wow.fixture.toolsClasspath")}))
                $dependencies
            }

            wow {
                addCompilerDependency = false
            }
            $extra
            """,
        )
    }

    private fun context(root: File, module: String, context: String, aggregate: String) {
        val type = aggregate.replaceFirstChar { it.uppercase() }
        write(
            root,
            "$module/src/main/kotlin/$module/$type.kt",
            """
            package $module

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext
            import me.ahoo.wow.api.annotation.CreateAggregate

            @BoundedContext(name = "$context")
            object ${type}Context

            @CreateAggregate
            data class Create$type(val name: String)
            data class ${type}Created(val name: String)

            @AggregateRoot
            class $type(private val state: ${type}State) {
                fun onCommand(command: Create$type): ${type}Created = ${type}Created(command.name)
            }

            class ${type}State(val id: String) {
                var name: String? = null

                fun onSourcing(event: ${type}Created) { name = event.name }
            }
            """,
        )
    }

    private fun gradleProperties(root: File) = write(
        root,
        "gradle.properties",
        """
        kotlin.compiler.execution.strategy=in-process
        org.gradle.jvmargs=-Xmx1g
        """,
    )

    private fun runner(root: File, vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root)
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    @BeforeAll
    fun build(@TempDir directory: File) {
        projectDir = directory
        write(
            directory,
            "settings.gradle.kts",
            """rootProject.name = "services"; include("inventory", "shop", "warehouse", "clash")""",
        )
        gradleProperties(directory)
        module(
            directory,
            "inventory",
            plugins = "`maven-publish`",
            extra = """
                publishing {
                    publications { create<MavenPublication>("service") { from(components["java"]) } }
                    repositories { maven { url = uri(rootDir.resolve("repo")) } }
                }
            """,
        )
        context(directory, "inventory", "inventory", "stock")
        module(directory, "shop", dependencies = """wowPeers(project(":inventory"))""")
        context(directory, "shop", "shop", "cart")
        module(directory, "warehouse", dependencies = """wowPeers(project(":inventory"))""")
        context(directory, "warehouse", "inventory", "bin")
        module(
            directory,
            "clash",
            dependencies = """wowPeers(project(":inventory")); wowPeers(project(":warehouse"))"""
        )
        context(directory, "clash", "clash", "thing")
        result = runner(directory, ":shop:jar", ":inventory:publish").build()
    }

    @Test
    fun `the consumer's jar should hold the producer's merged manifest under the peers directory`() {
        result.task(":inventory:${WowPlugin.MERGE_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        result.task(":shop:${WowPlugin.COLLECT_PEERS_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        val produced = File(projectDir, "inventory/build/wow/manifest.json")
        ZipFile(File(projectDir, "shop/build/libs/shop-1.0.0.jar")).use { zip ->
            zip.entries().asSequence().map { it.name }.filter { it.startsWith(PeerManifests.DIRECTORY) }.toList()
                .assert().containsExactlyInAnyOrder(
                    "${PeerManifests.DIRECTORY}/",
                    PeerManifests.INDEX,
                    PeerManifests.resource("inventory"),
                )
            zip.getInputStream(zip.getEntry(PeerManifests.INDEX)).readBytes().decodeToString()
                .assert().isEqualTo("inventory.json\n")
            val peer = zip.getInputStream(zip.getEntry(PeerManifests.resource("inventory"))).readBytes()
            peer.assert().isEqualTo(produced.readBytes())
        }
        val manifest = ManifestJson.read(produced)
        manifest.service?.name.assert().isEqualTo("inventory")
        manifest.contexts.map { it.name }.assert().containsExactly("inventory")
    }

    @Test
    fun `the consumer's own merged manifest should not hold its peers`() {
        val merged = runner(projectDir, ":shop:${WowPlugin.MERGE_TASK}").build()
        merged.task(":shop:${WowPlugin.MERGE_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        ManifestJson.read(File(projectDir, "shop/build/wow/manifest.json")).contexts.map { it.name }
            .assert().containsExactly("shop")
    }

    @Test
    fun `the published service should carry its manifest as an artifact and a variant`() {
        val published = File(projectDir, "repo/sample/inventory/1.0.0")
        File(published, "inventory-1.0.0-${WowPlugin.MANIFEST_CLASSIFIER}.json").readBytes()
            .assert().isEqualTo(File(projectDir, "inventory/build/wow/manifest.json").readBytes())
        File(published, "inventory-1.0.0.module").readText().assert().contains(
            "\"name\": \"${WowPlugin.MANIFEST_ELEMENTS}\"",
            "\"org.gradle.docstype\": \"${WowPlugin.MANIFEST_DOCS_TYPE}\"",
            "\"url\": \"inventory-1.0.0-${WowPlugin.MANIFEST_CLASSIFIER}.json\"",
        )
    }

    @Test
    fun `a service should take a published peer by its coordinates`(@TempDir outside: File) {
        write(outside, "settings.gradle.kts", """rootProject.name = "outside"; include("orders")""")
        gradleProperties(outside)
        module(outside, "orders", dependencies = """wowPeers("sample:inventory:1.0.0")""")
        context(outside, "orders", "orders", "order")
        runner(outside, ":orders:jar").build()
        ZipFile(File(outside, "orders/build/libs/orders-1.0.0.jar")).use { zip ->
            zip.getInputStream(zip.getEntry(PeerManifests.resource("inventory"))).use(ManifestJson::read)
                .service?.name.assert().isEqualTo("inventory")
        }
    }

    @Test
    fun `a peer declaring a context of the consumer should fail the build`() {
        val failed = runner(projectDir, ":warehouse:${WowPlugin.COLLECT_PEERS_TASK}").buildAndFail()
        failed.output.assert().contains("context inventory is declared by service warehouse and by its peer inventory")
    }

    @Test
    fun `two peers declaring one context should fail the build`() {
        val failed = runner(projectDir, ":clash:${WowPlugin.COLLECT_PEERS_TASK}").buildAndFail()
        failed.output.assert().contains("context inventory is declared by the peers inventory (")
        failed.output.assert().contains(") and warehouse (")
    }
}
