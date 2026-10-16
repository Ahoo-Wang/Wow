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
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.net.URLClassLoader

/**
 * Handlers the processor used to drop silently (finding A4-2): each is now a compile error, or bound as it should be.
 */
@OptIn(ExperimentalCompilerApi::class)
class DroppedHandlersTest {
    private fun cart(root: String, state: String): SourceFile = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AfterCommand
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.OnCommand
        import me.ahoo.wow.api.annotation.OnSourcing
        import me.ahoo.wow.api.decide.Command

        @CreateAggregate
        data class Create(val id: String)
        data class Created(val id: String)
        data class Add(val item: String)
        data class Added(val item: String)

        @AggregateRoot
        class Cart(private val state: CartState) {
            fun onCommand(command: Create): Created = Created(command.id)
            $root
        }

        class CartState {
            fun onSourcing(event: Created) {}
            $state
        }
        """.trimIndent(),
    )

    private fun failing(root: String, state: String = "fun onSourcing(event: Added) {}"): String {
        val (_, result) = compileModel("generate", cart(root, state))
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        return result.messages
    }

    @Test
    fun `a private OnCommand function should be a compile error`() {
        failing("@OnCommand private fun onAdd(command: Add): Added = Added(command.item)").assert().contains(
            "onAdd is private and annotated @OnCommand: the generated model calls it, so it is public or internal",
        )
    }

    @Test
    fun `a private OnSourcing function should be a compile error`() {
        failing(
            "fun onCommand(command: Add): Added = Added(command.item)",
            state = "@OnSourcing private fun onAdded(event: Added) {}",
        ).assert().contains(
            "onAdded is private and annotated @OnSourcing: the generated model calls it, so it is public or internal",
        )
    }

    @Test
    fun `a command function taking Command of a star or of Any should be a compile error`() {
        failing("@OnCommand fun onAdd(command: Command<*>): Added = Added(\"x\")").assert().contains(
            "onAdd takes me.ahoo.wow.api.decide.Command<*>: a command function names the class of its command",
        )
        failing("@OnCommand fun onAdd(command: Command<Any>): Added = Added(\"x\")").assert().contains(
            "onAdd takes kotlin.Any: a command function names the class of its command",
        )
        failing("@OnCommand fun onAdd(command: Any): Added = Added(\"x\")").assert().contains(
            "onAdd takes kotlin.Any: a command function names the class of its command",
        )
    }

    @Test
    fun `a handler annotation on the wrong class should be a compile error`() {
        val messages = failing(
            """
            fun onCommand(command: Add): Added = Added(command.item)
            @OnSourcing fun onCreatedToo(event: Created) {}
            """.trimIndent(),
            state = """
                fun onSourcing(event: Added) {}
                @OnCommand fun onAddToo(command: Add): Added = Added(command.item)
                @AfterCommand fun audit(command: Any): List<Any> = emptyList()
            """.trimIndent(),
        )
        messages.assert().contains(
            "the state CartState's function onAddToo is annotated @OnCommand: command functions are the aggregate " +
                "root's, so move it to Cart",
            "the state CartState's function audit is annotated @AfterCommand: after hooks are the aggregate root's",
            "the aggregate root Cart's function onCreatedToo is annotated @OnSourcing: sourcing functions are the " +
                "state's, so move it to CartState",
        )
    }

    /** A processor base class with two handlers; the processor overrides one without repeating `@OnEvent`. */
    private val base = SourceFile.kotlin(
        "Base.kt",
        """
        package lib

        import me.ahoo.wow.api.annotation.Blocking
        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.subscribe.StateEvent

        abstract class Base {
            val seen = mutableListOf<String>()

            @Blocking
            @OnEvent(aggregates = ["other.cart"])
            open fun onOne(event: StateEvent<*>) { seen += "base.onOne" }

            @OnEvent
            open fun onTwo(event: StateEvent<*>) { seen += "base.onTwo" }
        }
        """.trimIndent(),
    )

    private val projection = SourceFile.kotlin(
        "Projection.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.Projection
        import me.ahoo.wow.api.subscribe.StateEvent

        @Projection
        class CartProjection : lib.Base() {
            override fun onOne(event: StateEvent<*>) { seen += "onOne" }
        }
        """.trimIndent(),
    )

    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    private fun handlers(compilation: KotlinCompilation, result: JvmCompilationResult): List<String> {
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val services = compilation.workingDir.walkTopDown()
            .first { it.isFile && it.name == ModelProviderSource.MODEL_PROVIDER }
        val root = services.parentFile.parentFile.parentFile
        val provider = ModelProvider.load(URLClassLoader(arrayOf(root.toURI().toURL()), result.classLoader)).single()
        return provider.processors(Dependencies.of()).map { "${it.name}:${it.blocking}:${it.aggregates}" }
    }

    @Test
    fun `a processor overriding a library handler without OnEvent should keep it`() {
        val library = compileLibrary(base)
        val (compilation, result) = compileModelAgainst("generate", options, listOf(library), projection)
        handlers(compilation, result).assert().containsExactlyInAnyOrder(
            "CartProjection.onOne:true:[other.cart]",
            "CartProjection.onTwo:false:[]",
        )
    }

    @Test
    fun `a processor base class of the module should be read through its processors only`() {
        val (compilation, result) = compileModel("generate", options, base, projection)
        handlers(compilation, result).assert().containsExactlyInAnyOrder(
            "CartProjection.onOne:true:[other.cart]",
            "CartProjection.onTwo:false:[]",
        )
    }
}
