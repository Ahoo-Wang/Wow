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

package me.ahoo.wow.compiler.model

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.wow.compiler.model.source.ModelProviderSource
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.spi.json.WowJson
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.net.URLClassLoader

/**
 * Deep review r1 (compiler): what the processor makes of an aggregate's shape, against what the kernel's model DSL
 * and the Manifest expect. Each test states the expected behaviour and fails on `ac58da98ac`.
 */
@OptIn(ExperimentalCompilerApi::class)
class R1AggregateModelTest {
    /** A cart whose root holds [root] and whose state holds [state], with [types] declared beside them. */
    private fun cart(types: String, root: String, state: String): SourceFile = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AfterCommand
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.OnSourcing

        @CreateAggregate
        data class Create(@me.ahoo.wow.api.annotation.AggregateId val id: String)
        data class Created(val id: String)

        $types

        @AggregateRoot
        class Cart(private val state: CartState) {
            fun onCommand(command: Create): Created = Created(command.id)
            $root
        }

        class CartState {
            fun onSourcing(event: Created) = Unit
            $state
        }
        """.trimIndent(),
    )

    private fun compile(vararg sources: SourceFile) =
        compileModel("generate", ManifestFragmentFixtures.context, *sources)

    private fun generated(compilation: KotlinCompilation, name: String): String =
        compilation.workingDir.walkTopDown().filter { it.name == name }.single().readText()

    private fun ok(result: JvmCompilationResult) =
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)

    /** The compile errors reported inside the generated sources, not by the processor's own diagnostics. */
    private fun generatedErrors(result: JvmCompilationResult): List<String> =
        result.messages.lines().filter { it.startsWith("e: ") && "/ksp/sources/" in it }

    /** A refusal by the processor's diagnostics: a compile error, none of it inside the generated sources. */
    private fun refused(result: JvmCompilationResult): String {
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        generatedErrors(result).assert().withFailMessage { "errors in generated code: ${generatedErrors(result)}" }
            .isEmpty()
        return result.messages
    }

    private fun models(compilation: KotlinCompilation, result: JvmCompilationResult) =
        ModelProvider.load(
            URLClassLoader(
                arrayOf(
                    compilation.workingDir.walkTopDown()
                        .first { it.isFile && it.name == ModelProviderSource.MODEL_PROVIDER }
                        .parentFile.parentFile.parentFile.toURI().toURL(),
                ),
                result.classLoader,
            ),
        ).single().models(Dependencies.NONE)

    /**
     * `SourcingResolver.sealedEvents` (`SourcingResolver.kt:98`) recurses into sealed subclasses without removing
     * duplicates: an event under two sealed parents is taken twice, and one sourcing function of the root type is
     * refused as two that "both take" it.
     */
    @Test
    fun `an event under two sealed parents should be sourced once by a function of their root type`() {
        val (_, result) = compile(
            cart(
                """
                sealed interface CartEvent
                sealed interface ItemEvent : CartEvent
                sealed interface PriceEvent : CartEvent
                data class Repriced(val price: Int) : ItemEvent, PriceEvent
                """.trimIndent(),
                "",
                "fun onSourcing(event: CartEvent) = Unit",
            ),
        )
        ok(result)
    }

    /**
     * The same expansion keeps an abstract subclass that is not sealed: the model evolves `ItemEvent` itself, the
     * Manifest lists it as an event, and its concrete subclasses have no evolve function. Taking that class directly is
     * refused as "not a concrete class"; through a sealed parent it is not.
     */
    @Test
    fun `an abstract subclass of a sealed sourcing type should not be bound as an event`() {
        val (compilation, result) = compile(
            cart(
                """
                sealed interface CartEvent
                abstract class ItemEvent : CartEvent
                data class ItemAdded(val sku: String) : ItemEvent()
                """.trimIndent(),
                "",
                "fun onSourcing(event: CartEvent) = Unit",
            ),
        )
        if (result.exitCode == KotlinCompilation.ExitCode.OK) {
            generated(compilation, "CartModel.kt").assert().doesNotContain("evolve<sample.ItemEvent>")
        } else {
            result.messages.assert().contains("ItemEvent")
        }
    }

    /**
     * `sourcingOf` checks duplicates among the bindings a sealed type expands to, never among the direct ones
     * (`SourcingResolver.kt:94`). Two sourcing functions of one event compile into two `evolve<Created>`, and the
     * kernel refuses the model when the host builds it. Two command functions of one command are a compile error.
     */
    @Test
    fun `two sourcing functions of one event should fail the compilation`() {
        val (compilation, result) = compile(cart("", "", "@OnSourcing fun onCreated(event: Created) = Unit"))
        val built = if (result.exitCode == KotlinCompilation.ExitCode.OK) {
            runCatching { models(compilation, result) }.exceptionOrNull()?.message ?: "built"
        } else {
            null
        }
        result.exitCode.assert().withFailMessage { "compiled; building the model: $built" }
            .isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        refused(result).assert().contains("Created")
    }

    /**
     * `EventYield.of` counts only the listed collection interfaces as containers (`EventYield.kt:57`). A command
     * function returning `ArrayList<Added>` (Kotlin's alias of `java.util.ArrayList`) is refused as producing
     * "java.util.ArrayList, which is not an event", while the kernel's `eventsOf` takes any `Iterable`.
     */
    @Test
    fun `a command function returning an ArrayList of events should compile`() {
        val (_, result) = compile(
            cart(
                """
                data class Add(val item: String)
                data class Added(val item: String)
                """.trimIndent(),
                "fun onCommand(command: Add): ArrayList<Added> = arrayListOf(Added(command.item))",
                "fun onSourcing(event: Added) = Unit",
            ),
        )
        ok(result)
    }

    /**
     * An after hook returning `Decision` is not refused (`AfterHookResolver.kt:94`): `EventYield.of(Decision)` is
     * null and nothing checks it. The kernel's `eventsOf` throws "An after hook returns events, not a decision" for
     * every command the hook runs after; the guide says a hook returns events and cannot reject.
     */
    @Test
    fun `an after hook returning a Decision should fail the compilation`() {
        val (_, result) = compile(
            cart(
                "",
                "@AfterCommand fun audit(command: Any): me.ahoo.wow.api.decide.Decision = " +
                    "me.ahoo.wow.api.decide.Decision.NoOp",
                "",
            ),
        )
        refused(result).assert().contains("audit")
    }

    /**
     * A state's secondary `()` constructor is not seen: only the primary or first constructor is
     * (`AggregateAnalyzer.kt:272`), so a state with one is refused as needing one.
     */
    @Test
    fun `a state with a secondary no-argument constructor should compile`() {
        val source = SourceFile.kotlin(
            "Cart.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class Create(@me.ahoo.wow.api.annotation.AggregateId val id: String)
            data class Created(val id: String)

            @AggregateRoot
            class Cart(private val state: CartState) {
                fun onCommand(command: Create): Created = Created(command.id)
            }

            class CartState(val items: Int) {
                constructor() : this(0)

                fun onSourcing(event: Created) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compile(source)
        ok(result)
    }

    /**
     * The generated factory is internal only when the root or the state is (`AggregateAnalyzer.kt:137`). An internal
     * command function's internal domain service makes the public factory expose an internal parameter type, and the
     * generated file does not compile.
     */
    @Test
    fun `an internal domain service of an internal command function should compile`() {
        val (_, result) = compile(
            cart(
                """
                data class Add(val item: String)
                data class Added(val item: String)
                internal class Pricing
                """.trimIndent(),
                "internal fun onCommand(command: Add, pricing: Pricing): Added = Added(command.item)",
                "fun onSourcing(event: Added) = Unit",
            ),
        )
        ok(result)
    }

    /**
     * A domain service's factory parameter is named after its class (`AggregateAnalyzer.kt:471`): a service class
     * named `Context` gives a second parameter `context` beside the factory's own (`ModelSource.kt:87`).
     */
    @Test
    fun `a domain service named Context should compile`() {
        val (_, result) = compile(
            cart(
                """
                data class Add(val item: String)
                data class Added(val item: String)
                class Context
                """.trimIndent(),
                "fun onCommand(command: Add, context: Context): Added = Added(command.item)",
                "fun onSourcing(event: Added) = Unit",
            ),
        )
        ok(result)
    }

    /**
     * `@Access` on a Kotlin `protected` member is checked for `private` alone (`AccessResolver.kt:140`): the
     * generated `access(...)` reads `state.department` and fails to compile inside the generated file, where a handler
     * gets a "protected" diagnostic naming it.
     */
    @Test
    fun `a protected access attribute should be refused, naming it`() {
        val (_, result) = compile(
            cart(
                "",
                "",
                "@me.ahoo.wow.api.annotation.Access protected val department: String = \"\"",
            ),
        )
        refused(result).assert().contains("department")
    }

    /**
     * A Java bean state's query fields are its getters: KSP shows its private fields as properties and the getters
     * as functions, and `QueryPropertiesSource` keeps public properties alone (`QueryPropertiesSource.kt:222`). The
     * generated `CartStateProperties` has none of the state's fields; `examples/java-smoke`'s `AccountStateProperties`
     * is empty the same way. Its KDoc promises "each public property … or computed (a getter alone)".
     */
    @Test
    fun `a Java bean state's getters should be its query fields`() {
        val root = SourceFile.kotlin(
            "Cart.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class Create(val owner: String)
            data class Created(val owner: String)

            @AggregateRoot
            class Cart(private val state: CartState) {
                fun onCommand(command: Create): Created = Created(command.owner)
            }
            """.trimIndent(),
        )
        val state = SourceFile.java(
            "CartState.java",
            """
            package sample;

            public class CartState {
                private String owner = "";

                public String getOwner() {
                    return owner;
                }

                public void onSourcing(Created event) {
                    owner = event.getOwner();
                }
            }
            """.trimIndent(),
        )
        val (compilation, result) = compile(root, state)
        ok(result)
        generated(compilation, "CartStateProperties.kt").assert().contains("\"state.owner\"")
    }

    /**
     * `OccurredAtResolver` reads a command's properties alone (`OccurredAtResolver.kt:52`), with none of the Java
     * handling `AggregateIdResolver` has: on a record component KSP shows no property, so `@OccurredAt` is dropped
     * without a word, and an import commits at "now" instead of when it occurred.
     */
    @Test
    fun `an OccurredAt record component should be read`() {
        val command = SourceFile.java(
            "ImportCart.java",
            """
            package sample;

            import me.ahoo.wow.api.annotation.AggregateId;
            import me.ahoo.wow.api.annotation.CreateAggregate;
            import me.ahoo.wow.api.annotation.OccurredAt;

            @CreateAggregate
            public record ImportCart(@AggregateId String id, @OccurredAt Long placedAt) {
            }
            """.trimIndent(),
        )
        val root = SourceFile.kotlin(
            "Cart.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Imported(val id: String)

            @AggregateRoot
            class Cart(val id: String) {
                fun onCommand(command: ImportCart): Imported = Imported(command.id())
                fun onSourcing(event: Imported) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compile(command, root)
        ok(result)
        generated(compilation, "CartModel.kt").assert().contains("occurredAt<sample.ImportCart>")
    }

    /**
     * The Manifest's `idProperty` of a record's `@AggregateId` component is the component's name
     * (`AggregateIdResolver.kt:151`), whatever Jackson writes: `@JsonProperty("cart")` on the component is ignored.
     * The HTTP client and `CommandBodies.of` read and write the id under `idProperty`.
     */
    @Test
    fun `a record's AggregateId should be named in the Manifest as Jackson writes it`() {
        val command = SourceFile.java(
            "RenameCart.java",
            """
            package sample;

            import com.fasterxml.jackson.annotation.JsonProperty;
            import me.ahoo.wow.api.annotation.AggregateId;

            public record RenameCart(@AggregateId @JsonProperty("cart") String cartId, String title) {
            }
            """.trimIndent(),
        )
        val root = SourceFile.kotlin(
            "Cart.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Renamed(val title: String)

            @AggregateRoot
            class Cart(val id: String) {
                fun onCommand(command: RenameCart): Renamed = Renamed(command.title())
                fun onSourcing(event: Renamed) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compile(command, root)
        ok(result)
        val fragment = compilation.workingDir.walkTopDown()
            .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        ManifestFragmentFixtures.read(fragment).contexts.single().aggregates.single().commands
            .single { it.name == "RenameCart" }.idProperty.assert().isEqualTo("cart")
    }

    /**
     * A state's base class's private field is not read (#6845, a limit found reviewing #6952): KSP gives a class no
     * private field of its supertypes, so `QueryPropertiesSource` sees the base's getter alone and names the query
     * field after it. A `@JsonProperty` (or `@JsonIgnore`) on the base's field is lost, whether the state is Java or
     * Kotlin: the generated field is `state.owner`, while Jackson writes `who`. Fails on `fix/java-sources` at
     * `4ac1831436` as well as on `ac58da98ac`.
     */
    @Test
    fun `a Jackson annotation on a state's base class's private field should name its query field`() {
        val root = SourceFile.kotlin(
            "Cart.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class Create(val owner: String)
            data class Created(val owner: String)

            @AggregateRoot
            class Cart(private val state: CartState) {
                fun onCommand(command: Create): Created = Created(command.owner)
            }
            """.trimIndent(),
        )
        val base = SourceFile.java(
            "CartBase.java",
            """
            package sample;

            public class CartBase {
                @com.fasterxml.jackson.annotation.JsonProperty("who") private String owner = "alice";
                public String getOwner() { return owner; }
            }
            """.trimIndent(),
        )
        val java = SourceFile.java(
            "CartState.java",
            """
            package sample;

            public class CartState extends CartBase {
                public void onSourcing(Created event) { }
            }
            """.trimIndent(),
        )
        val kotlin = SourceFile.kotlin(
            "CartState.kt",
            """
            package sample

            class CartState : CartBase() {
                fun onSourcing(event: Created) = Unit
            }
            """.trimIndent(),
        )
        listOf(java, kotlin).forEach { state ->
            val (compilation, result) = compile(root, base, state)
            ok(result)
            val instance = result.classLoader.loadClass("sample.CartState").getDeclaredConstructor().newInstance()
            WowJson.mapper.writeValueAsString(instance).assert().isEqualTo("""{"who":"alice"}""")
            generated(compilation, "CartStateProperties.kt").assert()
                .contains("\"state.who\"").doesNotContain("\"state.owner\"")
        }
    }
}
