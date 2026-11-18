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
import org.junit.jupiter.api.Test
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

/** A Java aggregate's sources, by their path under the Java root (`<root>/main/java`), and the Kotlin ones beside. */
private val JAVA_SOURCES: Map<String, String> = mapOf(
    // The context moves with the Java root, as the drill's did with its source set.
    "main/java/c1/C1Context.java" to """
        package c1;

        import me.ahoo.wow.api.annotation.BoundedContext;

        @BoundedContext(name = "c1")
        public final class C1Context {
        }
    """,
    "main/java/c1/OpenLedger.java" to """
        package c1;

        import me.ahoo.wow.api.annotation.CreateAggregate;

        @CreateAggregate
        public record OpenLedger(String owner) {
        }
    """,
    "main/java/c1/LedgerOpened.java" to """
        package c1;

        public record LedgerOpened(String owner) {
        }
    """,
    "main/java/c1/LedgerState.java" to """
        package c1;

        public class LedgerState {
            private String owner = "";

            public String getOwner() {
                return owner;
            }

            public void onSourcing(LedgerOpened event) {
                owner = event.owner();
            }
        }
    """,
    "main/java/c1/Ledger.java" to """
        package c1;

        import me.ahoo.wow.api.annotation.AggregateRoot;

        @AggregateRoot
        public class Ledger {
            private final LedgerState state;

            public Ledger(LedgerState state) {
                this.state = state;
            }

            public LedgerOpened onCommand(OpenLedger command) {
                return new LedgerOpened(command.owner());
            }
        }
    """,
)

/** The Kotlin sources of the Java case, under `src/`, which does not move: the specs. */
private val JAVA_CASE_KOTLIN: Map<String, String> = mapOf(
    "test/kotlin/c1/LedgerSpec.kt" to """
        package c1

        import org.junit.jupiter.api.Test

        class LedgerSpec {
            @Test
            fun `the ledger is of its context`() {
                check(LedgerModel.TYPE.context == LedgerModel.CONTEXT)
            }
        }
    """,
    "test/kotlin/c1/PlainSpec.kt" to SOURCES.getValue("test/kotlin/c1/PlainSpec.kt"),
)

/**
 * A module's source sets switched from one root to another holding the same files at the same relative paths, as the
 * staged migration's sources are switched back, then one incremental change, compared with a clean build of the same
 * files (Gradle TestKit): the generated sources and the test classes run.
 */
class SourceRootSwitchFunctionalTest {
    /**
     * The module's build: with the Kotlin and KSP plugins alone and Wow's processor ([PLAIN]), or with Wow's domain
     * plugin, its Kotlin roots switching ([WOW]) or its Java roots alone, a Java aggregate's ([WOW_JAVA]). The Java
     * root is a Kotlin root too, as the Kotlin plugin makes `src/main/java` one, so KSP reads the Java sources.
     */
    enum class Build { PLAIN, WOW, WOW_JAVA }

    private fun buildFile(build: Build, root: String): String {
        val kotlinRoot = if (build == Build.WOW_JAVA) "src" else root
        val javaRoot = if (build == Build.WOW_JAVA) root else "src"
        val junit = System.getProperty("wow-test.junit.version")
        val classpath = ContextsFixture.classpath("wow-test.fixture.compileClasspath")
        val processor = ContextsFixture.classpath("wow-test.fixture.processorClasspath")
        val plugins = when (build) {
            Build.PLAIN -> "id(\"org.jetbrains.kotlin.jvm\")\n    id(\"com.google.devtools.ksp\")"
            Build.WOW, Build.WOW_JAVA -> "id(\"org.jetbrains.kotlin.jvm\")\n    id(\"${WowPlugin.ID}\")"
        }
        val wow = when (build) {
            Build.PLAIN -> "ksp { arg(\"wow.model\", \"generate\") }"
            Build.WOW, Build.WOW_JAVA ->
                "dependencies { wowTools(files(${ContextsFixture.classpath("wow-test.fixture.toolsClasspath")})) }"
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
                    named("main") { kotlin.setSrcDirs(listOf("$kotlinRoot/main/kotlin", "$javaRoot/main/java")) }
                    named("test") { kotlin.setSrcDirs(listOf("$kotlinRoot/test/kotlin", "$javaRoot/test/java")) }
                }
            }

            sourceSets {
                named("main") { java.setSrcDirs(listOf("$javaRoot/main/java")) }
                named("test") { java.setSrcDirs(listOf("$javaRoot/test/java")) }
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
        val moving = if (build == Build.WOW_JAVA) JAVA_SOURCES else SOURCES
        if (build == Build.WOW_JAVA) {
            JAVA_CASE_KOTLIN.forEach { (path, content) -> ContextsFixture.write(dir, "src/$path", content) }
        }
        moving.forEach { (path, content) -> ContextsFixture.write(dir, "staged/$path", content) }
        write(dir, build, "staged")
        run(dir).passed.assert().isTrue()
        moving.forEach { (path, content) -> ContextsFixture.write(dir, "src/$path", content) }
        write(dir, build, "src")
        run(dir).passed.assert().isTrue()
        val (root, aggregate) = if (build == Build.WOW_JAVA) "Ledger.java" to "Ledger" else "Ship.kt" to "Ship"
        val edited = File(dir, "src/main/${if (build == Build.WOW_JAVA) "java" else "kotlin"}/c1/$root")
        edited.writeText(edited.readText().replace("@AggregateRoot", "// Edited.\n@AggregateRoot"))
        ContextsFixture.write(
            dir,
            "src/main/kotlin/c1/Import$aggregate.kt",
            """
            package c1

            import me.ahoo.wow.api.annotation.MountedCommand

            @MountedCommand(aggregate = "${aggregate.lowercase()}", transformsTo = [${if (build == Build.WOW_JAVA) "OpenLedger" else "CreateShip"}::class])
            data class Import$aggregate(val title: String)
            """,
        )
        val incremental = run(dir)
        runner(dir, "clean").build()
        return incremental to run(dir)
    }

    @ParameterizedTest
    @EnumSource(Build::class, names = ["WOW", "WOW_JAVA"])
    fun `an incremental build after the source roots switch should end as a clean build does`(
        build: Build,
        @TempDir dir: File,
    ) {
        val (incremental, clean) = switched(dir, build)
        clean.passed.assert().isTrue()
        // Wow's plugin makes the source roots an input of both tasks: the first run after the switch rebuilds them.
        incremental.assert().describedAs("$build").isEqualTo(clean)
    }

    /**
     * Kotlin and KSP alone, with Wow's processor and no Wow plugin, keep their caches of the old root: the model is
     * written without its context's default and names object, and the spec using it drops out of the run, the build
     * green. The fault is upstream's, and [SourceRoots] works around it. This test pins it as a tripwire: **when it fails,
     * upstream no longer drops outputs after a root switch**, which is good news; remove [SourceRoots] and this test, or
     * keep [SourceRoots] and say why.
     */
    @Test
    fun `Kotlin and KSP alone should still drop outputs after a source root switch, until upstream fixes it`(
        @TempDir dir: File,
    ) {
        val (incremental, clean) = switched(dir, Build.PLAIN)
        clean.passed.assert().isTrue()
        val fixed = "Kotlin and KSP no longer drop outputs after a source root switch (incremental: $incremental, " +
            "clean: $clean): upstream fixed it, so SourceRoots can go with this test, or stay, saying why."
        incremental.passed.assert().withFailMessage(fixed).isTrue()
        incremental.classes.assert().withFailMessage(fixed).containsExactly("c1.PlainSpec")
        incremental.generated.assert().withFailMessage(fixed).isNotEqualTo(clean.generated)
        // The processor's warning makes the round seen: it names the stale caches and the way out.
        incremental.noContext.assert().isTrue()
        clean.noContext.assert().isFalse()
    }
}
