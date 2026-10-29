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
 * The Manifest gate on a single-module service built with the plugin (Gradle TestKit): `check` fails without a
 * baseline; the recorded baseline holds the compatibility line, not the version, so a new minor alone keeps it current;
 * an additive change fails until it is recorded, and a breaking one fails with the change's path within the line, each
 * naming `updateWowManifestBaseline`; the next major records a break, and so does a pre-release of a major's first
 * version.
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
    fun `the gate should need a baseline, record additive changes and fail breaking ones with their paths`() {
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
                id("me.ahoo.wow.service")
            }

            version = "1.0.0"

            repositories {
                mavenCentral()
                maven { url = uri("${System.getProperty("wow.fixture.repository")}") }
            }

            dependencies {
                implementation(files(${classpath("wow.fixture.compileClasspath")}))
                ksp(files(${classpath("wow.fixture.processorClasspath")}))
                wowTools(files(${classpath("wow.fixture.toolsClasspath")}))
            }
            """,
        )
        writeCart(addItem = "val productId: String, val quantity: Int", itemAdded = "val productId: String")
        val baseline = File(projectDir, WowPlugin.BASELINE_FILE)
        val report = File(projectDir, "build/wow/manifest-compatibility.txt")

        val missing = runner("check").buildAndFail()
        missing.output.assert().contains(
            "project ':' has no Manifest baseline (baseline.json): run updateWowManifestBaseline of project ':'",
        )

        runner(WowPlugin.UPDATE_BASELINE_TASK).build()
        baseline.readText().assert().contains("\"name\" : \"AddItem\"").doesNotContain("1.0.0")
        runner("check").build().task(":${WowPlugin.CHECK_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)

        // A new version alone: the baseline records no version, so it stays current.
        File(projectDir, "build.gradle.kts").apply { writeText(readText().replace("1.0.0", "1.1.0")) }
        runner("check").build().task(":${WowPlugin.CHECK_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)

        // An optional field of the command and a new field of the event keep the contract, but the baseline must
        // record them.
        writeCart(
            addItem = "val productId: String, val quantity: Int, val note: String? = null",
            itemAdded = "val productId: String, val at: Long = 0",
        )
        runner("check").buildAndFail().output.assert().contains(
            "The Manifest baseline of project ':' does not record the Manifest this build produces",
            "run updateWowManifestBaseline of project ':', review the diff and commit it",
        )
        report.readText().assert().contains(
            "Manifest against its baseline: 0 breaking, 2 additive",
            "ADDITIVE contexts[shop].aggregates[cart].commands[AddItem].schema.note: optional field added",
            "ADDITIVE contexts[shop].aggregates[cart].events[ItemAdded].schema.at: optional field added",
        )
        runner(WowPlugin.UPDATE_BASELINE_TASK, "check").build()
            .task(":${WowPlugin.CHECK_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)

        // The command loses its quantity: a client that sends it is refused.
        writeCart(
            addItem = "val productId: String, val note: String? = null",
            itemAdded = "val productId: String, val at: Long = 0",
        )
        val breaking = runner("check").buildAndFail()
        breaking.task(":${WowPlugin.CHECK_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.FAILED)
        breaking.output.assert().contains(
            "The Manifest of project ':' has 1 breaking change(s) within its compatibility line 1 (version 1.1.0)",
            "  - contexts[shop].aggregates[cart].commands[AddItem].schema.quantity: field removed",
            "a break needs the service's next major version, then run updateWowManifestBaseline of project ':'",
        )
        // Nor does the baseline record it within the line.
        runner(WowPlugin.UPDATE_BASELINE_TASK).buildAndFail().output.assert()
            .contains("project ':' refuses to record 1 breaking change(s) within its compatibility line 1")

        // The next major may break: the baseline records it on the line 2.
        File(projectDir, "build.gradle.kts").apply { writeText(readText().replace("1.1.0", "2.0.0")) }
        runner(WowPlugin.UPDATE_BASELINE_TASK, "check").build()
            .task(":${WowPlugin.CHECK_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        baseline.readText().assert().contains("\"line\" : 2")

        // A pre-release of a major's first version keeps the line open: the event loses a field, and it records.
        File(projectDir, "build.gradle.kts").apply { writeText(readText().replace("2.0.0", "3.0.0-alpha.1")) }
        writeCart(addItem = "val productId: String, val note: String? = null", itemAdded = "val productId: String")
        runner(WowPlugin.UPDATE_BASELINE_TASK, "check").build()
            .task(":${WowPlugin.CHECK_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        baseline.readText().assert().contains("\"line\" : 3")
    }
}
