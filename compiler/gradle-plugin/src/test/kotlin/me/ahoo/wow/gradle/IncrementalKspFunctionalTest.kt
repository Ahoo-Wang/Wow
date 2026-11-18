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

private fun aggregate(pkg: String, name: String) = """
    package $pkg

    import me.ahoo.wow.api.annotation.AggregateRoot
    import me.ahoo.wow.api.annotation.CreateAggregate

    @CreateAggregate
    data class Create$name(val title: String)
    data class ${name}Created(val title: String)

    class ${name}State(val id: String) {
        var title: String = ""
            private set

        fun onSourcing(event: ${name}Created) { title = event.title }
    }

    @AggregateRoot
    class $name(private val state: ${name}State) {
        fun onCommand(command: Create$name): ${name}Created = ${name}Created(command.title)
    }
"""

private fun context(pkg: String, name: String, description: String = "") = """
    package $pkg

    import me.ahoo.wow.api.annotation.BoundedContext

    @BoundedContext(name = "$name", description = "$description")
    object ${name.uppercase()}Context
"""

/** The files of [IncrementalKspFunctionalTest]'s build whose context is named by the api's constant, by path. */
private val IMPORTED: Map<String, String> = mapOf(
    "settings.gradle.kts" to """rootProject.name = "imported"
include("api", "d1")""",
    "api/src/main/kotlin/c1/api/Names.kt" to """
            package c1.api

            object Names {
                const val CONTEXT = "c1"
            }
        """,
    "api/src/main/kotlin/c1/api/Ship.kt" to """
            package c1.api

            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class CreateShip(val title: String)
            data class ShipCreated(val title: String)
        """,
    "api/src/main/kotlin/c1/api/ImportShip.kt" to """
            package c1.api

            data class ImportShip(val title: String)
        """,
    "d1/src/main/kotlin/c1/Context.kt" to """
            package c1

            import c1.api.Names
            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = Names.CONTEXT, description = "Ships")
            object C1Context
        """,
    "d1/src/main/kotlin/c1/Ship.kt" to """
            package c1

            import c1.api.CreateShip
            import c1.api.ShipCreated
            import me.ahoo.wow.api.annotation.AggregateRoot

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
)

/**
 * An incremental build's outputs are a clean build's (Gradle TestKit). Each case builds once, makes one change, builds
 * incrementally, then cleans and builds again, and compares every file the modules' KSP and Manifest tasks wrote
 * (`build/generated/ksp/main`, `build/wow`), byte for byte, or, for the module's tests, how the build ends and which
 * test classes ran:
 * - two domain modules, `d1` (context `c1`: `Order`, `Ship`, `Cargo` whose state is a file of its own holding a class
 *   of a third, a saga) and `d2` (context `c2`, `Invoice`, depending on `d1`), and a change of [Change];
 * - an api module whose constant names the domain's context and whose command the domain takes as a mounted command,
 *   as a migrated project has them, and a change of [ImportedChange];
 * - the domain's tests, one spec using a generated model, and a change of [SpecChange] that removes or moves it.
 *
 * Written for a report of an incremental build missing a context's default and an aggregate's model, which none of these
 * reproduces: they guard what the processor declares to KSP about each output's origins (ModelSymbolProcessor).
 */
class IncrementalKspFunctionalTest {
    private fun file(root: File, path: String, content: String) = ContextsFixture.write(root, path, content)

    private val saga = """
        package c1

        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.client.CommandRequest
        import me.ahoo.wow.api.subscribe.StateTypedEvent

        @Saga
        class OrderSaga {
            @OnEvent
            fun onOrderCreated(event: StateTypedEvent<OrderState, OrderCreated>): CommandRequest<CreateOrder> =
                CommandRequest.of(CreateOrder(event.ref.id), event.ref.id + "-next")
        }
    """

    private fun build(root: File) {
        file(
            root,
            "settings.gradle.kts",
            """rootProject.name = "incremental"
include("d1", "d2")"""
        )
        ContextsFixture.properties(root)
        ContextsFixture.module(root, "d1", WowPlugin.ID, "")
        ContextsFixture.module(root, "d2", WowPlugin.ID, "implementation(project(\":d1\"))")
        file(root, "d1/src/main/kotlin/c1/Context.kt", context("c1", "c1"))
        file(root, "d1/src/main/kotlin/c1/Order.kt", aggregate("c1", "Order"))
        file(root, "d1/src/main/kotlin/c1/Ship.kt", aggregate("c1", "Ship"))
        file(root, "d1/src/main/kotlin/c1/OrderSaga.kt", saga)
        // An aggregate whose state is a file of its own, holding a class of a third file.
        file(
            root,
            "d1/src/main/kotlin/c1/Cargo.kt",
            """
            package c1

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class LoadCargo(val sku: String)
            data class CargoLoaded(val sku: String)

            @AggregateRoot
            class Cargo(private val state: CargoState) {
                fun onCommand(command: LoadCargo): CargoLoaded = CargoLoaded(command.sku)
            }
        """
        )
        file(
            root,
            "d1/src/main/kotlin/c1/CargoState.kt",
            """
            package c1

            class CargoState(val id: String) {
                var items: List<Item> = emptyList()
                    private set

                fun onSourcing(event: CargoLoaded) { items = items + Item(event.sku) }
            }
        """
        )
        file(root, "d1/src/main/kotlin/c1/Item.kt", "package c1\n\ndata class Item(val sku: String)\n")
        file(root, "d2/src/main/kotlin/c2/Context.kt", context("c2", "c2"))
        file(root, "d2/src/main/kotlin/c2/Invoice.kt", aggregate("c2", "Invoice"))
    }

    /** A change between the first build and the incremental one. */
    @Suppress("unused")
    enum class Change(val apply: (File) -> Unit) {
        DELETE_AGGREGATE({ File(it, "d1/src/main/kotlin/c1/Ship.kt").delete() }),
        DELETE_SAGA({ File(it, "d1/src/main/kotlin/c1/OrderSaga.kt").delete() }),
        ADD_AGGREGATE({ root ->
            ContextsFixture.write(root, "d1/src/main/kotlin/c1/Boat.kt", aggregate("c1", "Boat"))
        }),
        MOVE_AGGREGATE_OUT({ root ->
            val ship = File(root, "d1/src/main/kotlin/c1/Ship.kt")
            ContextsFixture.write(root, "d2/src/main/kotlin/c1/Ship.kt", ship.readText())
            ship.delete()
        }),
        RENAME_AGGREGATE({ root ->
            val ship = File(root, "d1/src/main/kotlin/c1/Ship.kt")
            ship.writeText(ship.readText().replace("Ship", "Vessel"))
        }),
        CHANGE_CONTEXT({ root ->
            ContextsFixture.write(root, "d1/src/main/kotlin/c1/Context.kt", context("c1", "c1", "Orders"))
        }),

        // A class a state holds changes: the state's query fields follow it.
        CHANGE_NESTED_TYPE({ root ->
            ContextsFixture.write(
                root,
                "d1/src/main/kotlin/c1/Item.kt",
                "package c1\n\ndata class Item(val sku: String, val count: Int = 1)\n"
            )
        }),

        // The state's own file changes, its root's untouched.
        CHANGE_STATE_FILE({ root ->
            val state = File(root, "d1/src/main/kotlin/c1/CargoState.kt")
            state.writeText(
                state.readText().replace(
                    "    var items",
                    "    var weight: Int = 0\n        private set\n\n    var items"
                )
            )
        }),
        CHANGE_DEPENDENCY({ root ->
            val order = File(root, "d1/src/main/kotlin/c1/Order.kt")
            order.writeText(
                order.readText().replace(
                    "data class OrderCreated(val title: String)",
                    "data class OrderCreated(val title: String, val note: String = \"\")"
                )
            )
        }),
    }

    private fun runner(root: File, vararg arguments: String) = GradleRunner.create()
        .withProjectDir(root)
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    /** Every file the modules' KSP and Manifest tasks wrote, by path, with its text. */
    private fun outputs(
        root: File,
        vararg modules: String = arrayOf(
            "d1",
            "d2"
        )
    ): Map<String, String> = modules.flatMap { module ->
        listOf("build/generated/ksp/main", "build/wow").flatMap { dir ->
            File(root, "$module/$dir").walkTopDown().filter { it.isFile }
                .map { "$module/$dir/" + it.relativeTo(File(root, "$module/$dir")).invariantSeparatorsPath to it.readText() }
                .toList()
        }
    }.toMap().toSortedMap()

    private val tasks = arrayOf(
        ":d1:${WowPlugin.GENERATE_FRAGMENT_TASK}",
        ":d2:${WowPlugin.GENERATE_FRAGMENT_TASK}",
        "assemble"
    )

    /**
     * A build whose context's name is a constant of the api module, and a command the api declares that the domain's
     * aggregate takes, as a migrated project has them: `api` (`Names.CONTEXT`, `CreateShip`, `ImportShip`) and `d1`.
     */
    private fun imported(root: File) {
        IMPORTED.forEach { (path, content) -> file(root, path, content) }
        ContextsFixture.properties(root)
        ContextsFixture.module(root, "api", WowPlugin.API_ID, "")
        ContextsFixture.module(root, "d1", WowPlugin.ID, "api(project(\":api\"))")
        file(root, "d1/src/main/kotlin/c1/Order.kt", aggregate("c1", "Order"))
    }

    /** A change of [imported]'s build between the first build and the incremental one. */
    @Suppress("unused")
    enum class ImportedChange(val apply: (File) -> Unit) {
        // The api's command moves into the domain, as a mounted command its aggregate takes.
        MOVE_MOUNTED_COMMAND_IN({ root ->
            File(root, "api/src/main/kotlin/c1/api/ImportShip.kt").delete()
            ContextsFixture.write(
                root,
                "d1/src/main/kotlin/c1/ImportShip.kt",
                """
                package c1

                import c1.api.CreateShip
                import me.ahoo.wow.api.annotation.MountedCommand

                @MountedCommand(aggregate = "ship", transformsTo = [CreateShip::class])
                data class ImportShip(val title: String)
                """,
            )
        }),

        // A file of the api changes, the one whose constant names the context untouched.
        CHANGE_API({ root ->
            ContextsFixture.write(
                root,
                "api/src/main/kotlin/c1/api/Extra.kt",
                "package c1.api\n\ndata class Extra(val v: Int)\n"
            )
        }),

        // A domain file is added that takes nothing new.
        ADD_DOMAIN_FILE({ root ->
            ContextsFixture.write(root, "d1/src/main/kotlin/c1/Util.kt", "package c1\n\nobject Util\n")
        }),
    }

    private val importedTasks = arrayOf(":d1:${WowPlugin.GENERATE_FRAGMENT_TASK}", "assemble")

    @ParameterizedTest
    @EnumSource(ImportedChange::class)
    fun `an incremental build of a context named by the api's constant should write what a clean build writes`(
        change: ImportedChange,
        @TempDir root: File,
    ) {
        imported(root)
        runner(root, *importedTasks).build()
        change.apply(root)
        runner(root, *importedTasks).build()
        val incremental = outputs(root, "d1")
        runner(root, "clean").build()
        runner(root, *importedTasks).build()
        val clean = outputs(root, "d1")
        val differing = (incremental.keys + clean.keys).filter { incremental[it] != clean[it] }.sorted()
        differing.map {
            "$it: incremental ${incremental[it]?.let { "has it" } ?: "lacks it"}, clean ${clean[it]?.let { "has it" } ?: "lacks it"}"
        }
            .assert().describedAs(change.name).isEmpty()
    }

    /** [module]'s tests on JUnit: one spec using the generated `ShipModel`, one using nothing generated. */
    private fun specs(root: File, module: String) {
        val junit = System.getProperty("wow-test.junit.version")
        File(root, "$module/build.gradle.kts").appendText(
            """

            dependencies {
                testImplementation(platform("org.junit:junit-bom:$junit"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            tasks.named<Test>("test") { useJUnitPlatform() }
            """.trimIndent(),
        )
        file(
            root,
            "$module/src/test/kotlin/c1/ShipSpec.kt",
            """
            package c1

            import org.junit.jupiter.api.Test

            class ShipSpec {
                @Test
                fun `the ship is of its context`() {
                    check(ShipModel.TYPE.context == ShipModel.CONTEXT)
                }
            }
        """
        )
        file(
            root,
            "$module/src/test/kotlin/c1/PlainSpec.kt",
            """
            package c1

            import org.junit.jupiter.api.Test

            class PlainSpec {
                @Test
                fun `nothing generated`() {
                    check(1 + 1 == 2)
                }
            }
        """
        )
    }

    /** What a build of [tasks] ends in: whether it passed, and the test classes [module]'s test task ran. */
    private data class TestRun(val passed: Boolean, val classes: List<String>)

    private fun testRun(root: File, module: String, vararg tasks: String): TestRun {
        // A run that fails before testing leaves the results of the one before.
        File(root, "$module/build/test-results").deleteRecursively()
        val result = runner(root, *tasks).run()
        val passed = result.output.contains("BUILD SUCCESSFUL")
        val classes = File(root, "$module/build/test-results/test").listFiles().orEmpty()
            .filter { it.name.endsWith(".xml") }.map { it.name.removePrefix("TEST-").removeSuffix(".xml") }.sorted()
        return TestRun(passed, classes)
    }

    /** A change that removes or moves what a spec of the module's tests uses. */
    @Suppress("unused")
    enum class SpecChange(val imported: Boolean, val apply: (File) -> Unit) {
        // The aggregate the spec names is deleted: its model is gone, so the spec no longer compiles.
        DELETE_AGGREGATE(false, { File(it, "d1/src/main/kotlin/c1/Ship.kt").delete() }),

        // The aggregate moves to the other module: the spec's module no longer has its model.
        MOVE_AGGREGATE_OUT(false, { root ->
            val ship = File(root, "d1/src/main/kotlin/c1/Ship.kt")
            ContextsFixture.write(root, "d2/src/main/kotlin/c1/Ship.kt", ship.readText())
            ship.delete()
        }),

        // The api's command moves into the domain as a mounted command: the spec's model stays.
        MOVE_MOUNTED_COMMAND_IN(true, { ImportedChange.MOVE_MOUNTED_COMMAND_IN.apply(it) }),
    }

    @ParameterizedTest
    @EnumSource(SpecChange::class)
    fun `an incremental build of the module's tests should end as a clean build does`(
        change: SpecChange,
        @TempDir root: File,
    ) {
        if (change.imported) imported(root) else build(root)
        specs(root, "d1")
        val tasks = arrayOf(":d1:test", "--continue")
        testRun(root, "d1", *tasks).passed.assert().isTrue()
        change.apply(root)
        val incremental = testRun(root, "d1", *tasks)
        // The test source set runs no processor: the specs compile against the main source set's generated code.
        File(root, "d1/build/generated/ksp/test").exists().assert().isFalse()
        runner(root, "clean").build()
        val clean = testRun(root, "d1", *tasks)
        // A spec whose model is gone fails the test compile, incrementally as in a clean build; none drops out.
        incremental.assert().describedAs(change.name).isEqualTo(clean)
        clean.passed.assert().isEqualTo(change.imported)
    }

    @ParameterizedTest
    @EnumSource(Change::class)
    fun `an incremental build should write what a clean build writes`(change: Change, @TempDir root: File) {
        build(root)
        runner(root, *tasks).build()
        change.apply(root)
        runner(root, *tasks).build()
        val incremental = outputs(root)
        runner(root, "clean").build()
        runner(root, *tasks).build()
        val clean = outputs(root)
        val differing = (incremental.keys + clean.keys).filter { incremental[it] != clean[it] }.sorted()
        differing.map {
            "$it: incremental ${incremental[it]?.let { "has it" } ?: "lacks it"}, clean ${clean[it]?.let { "has it" } ?: "lacks it"}"
        }
            .assert().describedAs(change.name).isEmpty()
    }
}
