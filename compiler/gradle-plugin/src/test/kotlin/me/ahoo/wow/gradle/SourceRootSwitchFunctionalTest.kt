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
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File

/** The module's sources, by their path under a source root (`<root>/main/kotlin`, `<root>/test/kotlin`). */
private val SOURCES: Map<String, String> = mapOf(
    "main/kotlin/c1/Context.kt" to """
        package c1

        import me.ahoo.wow.api.annotation.BoundedContext

        @BoundedContext(name = "c1")
        object C1Context
    """,
    "main/kotlin/c1/Ship.kt" to """
        package c1

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate

        @CreateAggregate
        data class CreateShip(val title: String)
        data class ShipCreated(val title: String)

        class ShipState(val id: String) {
            var title: String = ""
                private set

            fun onSourcing(event: ShipCreated) { title = event.title }
        }

        @AggregateRoot
        class Ship(private val state: ShipState) {
            fun onCommand(command: CreateShip): ShipCreated = ShipCreated(command.title)
        }
    """,
    "test/kotlin/c1/ShipSpec.kt" to """
        package c1

        import org.junit.jupiter.api.Test

        class ShipSpec {
            @Test
            fun `the ship is of its context`() {
                check(ShipModel.TYPE.context == ShipModel.CONTEXT)
            }
        }
    """,
    "test/kotlin/c1/PlainSpec.kt" to """
        package c1

        import org.junit.jupiter.api.Test

        class PlainSpec {
            @Test
            fun `nothing generated`() {
                check(1 + 1 == 2)
            }
        }
    """,
)

/**
 * A module's source sets switched from one root to another holding the same files at the same relative paths, as the
 * staged migration's sources are switched back, then one incremental change, compared with a clean build of the same
 * files (Gradle TestKit): the generated sources and the test classes run.
 */
class SourceRootSwitchFunctionalTest {
    /** The module's build: with Wow's domain plugin, or with the Kotlin and KSP plugins alone and Wow's processor. */
    enum class Build { PLAIN, WOW }

    private fun buildFile(build: Build, root: String): String {
        val junit = System.getProperty("wow-test.junit.version")
        val classpath = ContextsFixture.classpath("wow-test.fixture.compileClasspath")
        val processor = ContextsFixture.classpath("wow-test.fixture.processorClasspath")
        val plugins = when (build) {
            Build.PLAIN -> "id(\"org.jetbrains.kotlin.jvm\")\n    id(\"com.google.devtools.ksp\")"
            Build.WOW -> "id(\"org.jetbrains.kotlin.jvm\")\n    id(\"${WowPlugin.ID}\")"
        }
        val wow = when (build) {
            Build.PLAIN -> "ksp { arg(\"wow.model\", \"generate\") }"
            Build.WOW -> "dependencies { wowTools(files(${ContextsFixture.classpath(
                "wow-test.fixture.toolsClasspath"
            )})) }"
        }
        return """
            plugins {
                $plugins
            }

            repositories {
                mavenCentral()
                maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }
            }

            dependencies {
                implementation(files($classpath))
                ksp(files($processor))
                testImplementation(platform("org.junit:junit-bom:$junit"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }

            $wow

            kotlin {
                sourceSets {
                    named("main") { kotlin.setSrcDirs(listOf("$root/main/kotlin")) }
                    named("test") { kotlin.setSrcDirs(listOf("$root/test/kotlin")) }
                }
            }

            tasks.named<Test>("test") { useJUnitPlatform() }
        """
    }

    private fun write(dir: File, build: Build, root: String) {
        ContextsFixture.write(dir, "settings.gradle.kts", "rootProject.name = \"switch\"")
        ContextsFixture.properties(dir)
        ContextsFixture.write(dir, "build.gradle.kts", buildFile(build, root))
    }

    private fun runner(dir: File, vararg arguments: String) = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    /**
     * How a build of the tests ends: whether it passed, the test classes run, what KSP generated, and whether the
     * processor warned that it saw no bounded context.
     */
    private data class Run(
        val passed: Boolean,
        val classes: List<String>,
        val generated: Map<String, String>,
        val noContext: Boolean,
    )

    private fun run(dir: File): Run {
        File(dir, "build/test-results").deleteRecursively()
        val result = runner(dir, "test", "--continue").run()
        val classes = File(dir, "build/test-results/test").listFiles().orEmpty()
            .filter { it.name.endsWith(".xml") }.map { it.name.removePrefix("TEST-").removeSuffix(".xml") }.sorted()
        val generated = File(dir, "build/generated/ksp/main").walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }.toSortedMap()
        val noContext = result.output.contains("No Manifest fragment and no model provider")
        return Run(result.output.contains("BUILD SUCCESSFUL"), classes, generated, noContext)
    }

    /**
     * Built from `staged/`, switched to `src/` with the same files (the old copies left), then the aggregate's file
     * edited and a mounted command added: the incremental run and a clean build of the same files.
     */
    private fun switched(dir: File, build: Build): Pair<Run, Run> {
        SOURCES.forEach { (path, content) -> ContextsFixture.write(dir, "staged/$path", content) }
        write(dir, build, "staged")
        run(dir).passed.assert().isTrue()
        SOURCES.forEach { (path, content) -> ContextsFixture.write(dir, "src/$path", content) }
        write(dir, build, "src")
        run(dir).passed.assert().isTrue()
        val ship = File(dir, "src/main/kotlin/c1/Ship.kt")
        ship.writeText(ship.readText().replace("data class ShipCreated", "// Edited.\ndata class ShipCreated"))
        ContextsFixture.write(
            dir,
            "src/main/kotlin/c1/ImportShip.kt",
            """
            package c1

            import me.ahoo.wow.api.annotation.MountedCommand

            @MountedCommand(aggregate = "ship", transformsTo = [CreateShip::class])
            data class ImportShip(val title: String)
            """,
        )
        val incremental = run(dir)
        runner(dir, "clean").build()
        return incremental to run(dir)
    }

    @ParameterizedTest
    @EnumSource(Build::class)
    fun `an incremental build after the source roots switch should end as a clean build does`(
        build: Build,
        @TempDir dir: File,
    ) {
        val (incremental, clean) = switched(dir, build)
        clean.passed.assert().isTrue()
        if (build == Build.PLAIN) {
            // Kotlin and KSP alone keep their caches of the old root: the model is written without its context's default
            // and names object, and the spec using it drops out of the run, the build green. Upstream's, not Wow's.
            incremental.passed.assert().isTrue()
            incremental.classes.assert().containsExactly("c1.PlainSpec")
            incremental.generated.assert().isNotEqualTo(clean.generated)
            // The processor's warning makes the round seen: it names the stale caches and the way out.
            incremental.noContext.assert().isTrue()
            clean.noContext.assert().isFalse()
        } else {
            // Wow's plugin makes the source roots an input of both tasks: the first run after the switch rebuilds them.
            incremental.assert().isEqualTo(clean)
        }
    }
}
