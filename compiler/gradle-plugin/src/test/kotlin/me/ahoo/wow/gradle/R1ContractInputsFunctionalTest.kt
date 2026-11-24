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
 * Deep review r1 (compiler, the Gradle plugin): `generateWowContract` refuses a context whose classes are in an api
 * module that does not pair with the domain module. Which api module a pairing file comes from is `@Internal`
 * (`GenerateContract.kt:79`), and the pairing files are an input by content alone (`@PathSensitive(NONE)`, `:74`), so
 * moving the `wowContract(...)` line from the api that holds the classes to another leaves the task UP-TO-DATE: the
 * refusal never fires, and the contract is lost without a word. Fails on `ac58da98ac`.
 */
class R1ContractInputsFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    private fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    private fun write(path: String, content: String) {
        File(projectDir, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    /** A module of the Kotlin plugin and the Wow plugin [id], with [dependencies] and [extra]. */
    private fun module(name: String, id: String, dependencies: String = "", extra: String = "") {
        val plugins = if (id == WowPlugin.API_ID) """id("$id"); `maven-publish`""" else """id("$id")"""
        val tools = if (id != WowPlugin.API_ID) {
            "ksp(files(${classpath(
                "wow-test.fixture.processorClasspath"
            )}))"
        } else {
            ""
        }
        write(
            "$name/build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                $plugins
            }

            group = "sample"
            version = "1.0.0"

            repositories {
                ${FixtureRepositories.central}
                maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }
            }

            dependencies {
                implementation(files(${classpath("wow-test.fixture.compileClasspath")}))
                $tools
                $dependencies
            }
            configurations.matching { it.name == "wowTools" }.configureEach {
                project.dependencies.add(name, files(${classpath("wow-test.fixture.toolsClasspath")}))
            }
            $extra
            """,
        )
    }

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    /**
     * `x-domain`, whose context's classes are in `a-api`, which pairs with it; `b-api` is on its class path and pairs
     * with nothing.
     */
    private fun pairedBuild() {
        write("settings.gradle.kts", """rootProject.name = "pairing"; include("a-api", "b-api", "x-domain")""")
        write(
            "gradle.properties",
            """
            kotlin.compiler.execution.strategy=in-process
            org.gradle.jvmargs=-Xmx1g
            """,
        )
        module("a-api", WowPlugin.API_ID, dependencies = PAIRS)
        module("b-api", WowPlugin.API_ID)
        write(
            "a-api/src/main/kotlin/xapi/Thing.kt",
            """
            package xapi

            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class CreateThing(val name: String)
            data class ThingCreated(val name: String)
            """,
        )
        module(
            "x-domain",
            WowPlugin.ID,
            dependencies = """api(project(":a-api")); implementation(project(":b-api"))""",
            extra = """wow { contractPackage = "xapi" }""",
        )
        write(
            "x-domain/src/main/kotlin/x_domain/Thing.kt",
            """
            package x_domain

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext
            import xapi.CreateThing
            import xapi.ThingCreated

            @BoundedContext(name = "x")
            object ThingContext

            @AggregateRoot
            class Thing(private val state: ThingState) {
                fun onCommand(command: CreateThing): ThingCreated = ThingCreated(command.name)
            }

            class ThingState(val id: String) {
                var name: String? = null

                fun onSourcing(event: ThingCreated) { name = event.name }
            }
            """,
        )
    }

    @Test
    fun `moving the pairing to an api module that holds none of the classes should fail the contract`() {
        pairedBuild()
        val task = ":x-domain:${WowPlugin.GENERATE_CONTRACT_TASK}"
        runner(task).build().task(task)?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)

        // The pairing moves to the api module that holds none of x's classes.
        module("a-api", WowPlugin.API_ID)
        module("b-api", WowPlugin.API_ID, dependencies = PAIRS)
        // The next build first, then the same task forced to run: a failed run would change its history.
        val incremental = runner(task).run()
        // As the task decides it when it runs: refused, naming the api that holds the classes.
        val rerun = runner(task, "--rerun").run()
        rerun.output.assert().contains("does not pair")
        incremental.task(task)?.outcome.assert().withFailMessage { "the second build's $task was UP-TO-DATE" }
            .isNotEqualTo(TaskOutcome.UP_TO_DATE)
        incremental.output.assert().contains("does not pair")
    }

    private companion object {
        const val PAIRS = """wowContract(project(":x-domain"))"""
    }
}
