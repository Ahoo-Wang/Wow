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

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/** Decisions D120 and D121: `@OnCommand(produces)` and the module's `@ErrorCode` constants in the fragment. */
@OptIn(ExperimentalCompilerApi::class)
class ProducesAndErrorCodeTest {
    private val context = SourceFile.kotlin(
        "OrdersContext.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.BoundedContext

        @BoundedContext(name = "orders")
        object OrdersContext
        """.trimIndent(),
    )

    private val order = SourceFile.kotlin(
        "Order.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AfterCommand
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.OnCommand
        import me.ahoo.wow.api.decide.Decision
        import reactor.core.publisher.Mono

        @CreateAggregate
        data class Place(val id: String)
        data class Cancel(val reason: String)
        data class Hold(val note: String)

        data class Placed(val id: String)
        data class Cancelled(val reason: String)
        data class Audited(val command: String)

        @AggregateRoot
        class Order(private val state: OrderState) {
            @OnCommand(produces = [Placed::class])
            fun onCommand(command: Place): Decision = Decision.events(Placed(command.id))

            @OnCommand(produces = [Cancelled::class, Placed::class, Cancelled::class])
            fun cancel(command: Cancel): Mono<Decision> = Mono.just(Decision.events(Cancelled(command.reason)))

            // Conventionally named, without the annotation: it cannot declare what it produces.
            fun onCommand(command: Hold): Decision = Decision.NoOp

            @AfterCommand(include = [Place::class])
            fun afterCommand(command: Any): Audited = Audited(command.javaClass.simpleName)
        }

        class OrderState(val id: String) {
            fun onSourcing(event: Placed) = Unit

            fun onSourcing(event: Cancelled) = Unit

            fun onSourcing(event: Audited) = Unit
        }
        """.trimIndent(),
    )

    private val errors = SourceFile.kotlin(
        "OrderErrors.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.ErrorCode

        object OrderErrors {
            /** The KDoc is not the description. */
            @ErrorCode(description = "Out of stock", status = 409)
            const val OUT_OF_STOCK = "orders.out-of-stock"

            @ErrorCode
            const val CLOSED: String =
                "orders.closed" // The literal may follow on the next line.
        }

        @ErrorCode(description = "Escapes are read")
        const val ESCAPED = "orders.abc\${'$'}"

        class Holder {
            companion object {
                @ErrorCode(status = 400) const val INVALID = "orders.invalid" }
        }
        """.trimIndent(),
    )

    private fun fragment(compilation: KotlinCompilation): Manifest {
        val file = compilation.workingDir.walkTopDown()
            .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        return ManifestTree.read(jacksonObjectMapper().readValue(file, Map::class.java))
    }

    @Test
    fun `@OnCommand(produces) should list the events of a function whose return type does not say`() {
        val (compilation, result) = compileModel("generate", context, order)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        fragment(compilation).contexts.single().aggregates.single().commands.associate { it.name to it.produces }
            .assert().isEqualTo(
                mapOf(
                    "Cancel" to listOf("Cancelled", "Placed"),
                    "Hold" to null,
                    // With the after hook that runs after it.
                    "Place" to listOf("Audited", "Placed"),
                ),
            )
    }

    @Test
    fun `@OnCommand(produces) should list events of the aggregate, on a function whose return type does not say`() {
        val wrong = SourceFile.kotlin(
            "Wrong.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.OnCommand
            import me.ahoo.wow.api.decide.Decision

            data class Post(val note: String)
            data class Edit(val note: String)
            data class Posted(val note: String)
            data class Stray(val note: String)

            @AggregateRoot
            class Journal(val id: String) {
                @OnCommand(produces = [Posted::class])
                fun onCommand(command: Post): Posted = Posted(command.note)

                @OnCommand(produces = [Posted::class, Stray::class])
                fun edit(command: Edit): Decision = Decision.events(Posted(command.note))

                fun onSourcing(event: Posted) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", context, wrong)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "onCommand returns sample.Posted, which names the events it produces: @OnCommand(produces) is for a " +
                "return type that does not, such as a Decision",
            "@OnCommand(produces) of edit(sample.Edit) lists sample.Stray, which is not an event of the aggregate",
        )
    }

    @Test
    fun `the module's error codes should be the context's errors`() {
        val (compilation, result) = compileModel("generate", context, order, errors)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        fragment(compilation).contexts.single().errors.assert().containsExactly(
            ErrorManifest("orders.abc$", 422, "Escapes are read"),
            ErrorManifest("orders.closed", 422),
            ErrorManifest("orders.invalid", 400),
            ErrorManifest("orders.out-of-stock", 409, "Out of stock"),
        )
    }

    @Test
    fun `a module of error codes only should have a fragment of them`() {
        val (compilation, result) = compileModel("generate", context, errors)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        fragment(compilation).contexts.single().let {
            it.aggregates.assert().isEmpty()
            it.errors.map(ErrorManifest::code).assert().hasSize(4)
        }
    }

    @Test
    fun `an error code should be a unique string literal outside the framework's prefix, with an error status`() {
        val bad = SourceFile.kotlin(
            "Bad.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.ErrorCode

            object Bad {
                @ErrorCode const val FRAMEWORK = "wow.command.invalid"
                @ErrorCode(status = 302) const val REDIRECT = "orders.redirect"
                @ErrorCode const val JOINED = "orders." + "joined"
                @ErrorCode val NOT_CONST = "orders.not-const"
                @ErrorCode const val NUMBER = 7
                @ErrorCode const val BLANK = " "
                @ErrorCode const val FIRST = "orders.same"
                @ErrorCode const val SECOND = "orders.same"
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", context, bad)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "error code wow.command.invalid of sample.Bad.FRAMEWORK takes the framework's prefix wow.",
            "error code orders.redirect of sample.Bad.REDIRECT has status 302: a status is 400 to 599",
            "@ErrorCode property sample.Bad.JOINED must be initialized with a string literal without templates",
            "@ErrorCode property sample.Bad.NOT_CONST must be a const val of type String",
            "@ErrorCode property sample.Bad.NUMBER must be a const val of type String",
            "@ErrorCode property sample.Bad.BLANK holds a blank code",
            "error code orders.same is declared by sample.Bad.",
        )
        compilation.workingDir.walkTopDown()
            .none { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
            .assert().isTrue()
    }

    @Test
    fun `the code should be read from a plain string literal only`() {
        val source = """
            object A {
                @ErrorCode
                const val X: kotlin.String = "a\tb\"c\\dA" ; val Y = 1
                const val T = "a${'$'}{X}"
                const val R = ${"\"\"\""}raw${"\"\"\""}
                const val U = "a\u00"
                const val E = "a\q"
                const val O = "open
            }
        """.trimIndent()
        ErrorCodeResolver.literal(source, 2, "X").assert().isEqualTo("a\tb\"c\\dA")
        ErrorCodeResolver.literal(source, 1, "T").assert().isNull()
        ErrorCodeResolver.literal(source, 1, "R").assert().isNull()
        ErrorCodeResolver.literal(source, 1, "U").assert().isNull()
        ErrorCodeResolver.literal(source, 1, "E").assert().isNull()
        ErrorCodeResolver.literal(source, 1, "O").assert().isNull()
        ErrorCodeResolver.literal(source, 1, "MISSING").assert().isNull()
        ErrorCodeResolver.literal("const val Z = \"a\\", 1, "Z").assert().isNull()
    }
}
