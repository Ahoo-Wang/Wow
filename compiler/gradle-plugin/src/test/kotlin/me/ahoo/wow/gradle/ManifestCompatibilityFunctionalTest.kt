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

/**
 * The Manifest compatibility gate on a service built with the plugin (Gradle TestKit): the baseline is recorded,
 * an additive change passes `check`, and a breaking one fails it with the change's path.
 */
class ManifestCompatibilityFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    private fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    private fun write(path: String, content: String) {
        File(projectDir, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    private fun writeCart(addItem: String, itemAdded: String) {
        write(
            "src/main/kotlin/sample/Cart.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext
            import me.ahoo.wow.api.annotation.CreateAggregate

            @BoundedContext(name = "shop")
            object ShopContext

            @CreateAggregate
            data class AddItem($addItem)

            data class ItemAdded($itemAdded)

            @AggregateRoot
            class Cart(private val state: CartState) {
                fun onCommand(command: AddItem): ItemAdded = ItemAdded(command.productId)
            }

            class CartState(val id: String) {
                var productId: String? = null

                fun onSourcing(event: ItemAdded) { productId = event.productId }
            }
            """,
        )
    }

    private fun runner(vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*tasks, "--stacktrace")
        .forwardOutput()

    @Test
    @Suppress("LongMethod") // The fixture and its three builds.
    fun `the gate should pass additive changes and fail breaking ones with their paths`() {
        write("settings.gradle.kts", """rootProject.name = "shop"""")
        write(
            "gradle.properties",
            """
            kotlin.compiler.execution.strategy=in-process
            org.gradle.jvmargs=-Xmx1g
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("me.ahoo.wow")
            }

            version = "1.0.0"

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation(files(${classpath("wow.fixture.compileClasspath")}))
                ksp(files(${classpath("wow.fixture.processorClasspath")}))
                wowManifestTools(files(${classpath("wow.fixture.toolsClasspath")}))
            }

            wow {
                addCompilerDependency = false
            }
            """,
        )
        writeCart(addItem = "val productId: String, val quantity: Int", itemAdded = "val productId: String")
        val baseline = File(projectDir, WowPlugin.BASELINE_FILE)
        val report = File(projectDir, "build/wow/manifest-compatibility.txt")

        val first = runner(WowPlugin.CHECK_COMPATIBILITY_TASK, WowPlugin.UPDATE_BASELINE_TASK).build()
        first.task(":${WowPlugin.CHECK_COMPATIBILITY_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        baseline.readText().assert().contains("\"name\" : \"AddItem\"")

        // An optional field of the command and a new field of the event, with the version moved on.
        File(projectDir, "build.gradle.kts").apply { writeText(readText().replace("1.0.0", "1.1.0")) }
        writeCart(
            addItem = "val productId: String, val quantity: Int, val note: String? = null",
            itemAdded = "val productId: String, val at: Long = 0",
        )
        val additive = runner("check").build()
        additive.task(":${WowPlugin.CHECK_COMPATIBILITY_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        report.readText().assert().contains(
            "Manifest 1.1.0 against the baseline 1.0.0: 0 breaking, 2 additive",
            "ADDITIVE contexts[shop].aggregates[cart].commands[AddItem].schema.note: optional field added",
            "ADDITIVE contexts[shop].aggregates[cart].events[ItemAdded].schema.at: optional field added",
        )

        // The command loses its quantity: a client that sends it is refused.
        writeCart(addItem = "val productId: String", itemAdded = "val productId: String")
        val breaking = runner("check").buildAndFail()
        breaking.task(":${WowPlugin.CHECK_COMPATIBILITY_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.FAILED)
        breaking.output.assert().contains(
            "The Manifest has 1 breaking change(s) against its baseline",
            "  - contexts[shop].aggregates[cart].commands[AddItem].schema.quantity: field removed",
        )

        // A new major version resets the baseline.
        File(projectDir, "build.gradle.kts").apply { writeText(readText().replace("1.1.0", "2.0.0")) }
        runner(WowPlugin.CHECK_COMPATIBILITY_TASK).build()
        report.readText().assert().contains("(a new major version: breaking changes are allowed)")
    }
}
