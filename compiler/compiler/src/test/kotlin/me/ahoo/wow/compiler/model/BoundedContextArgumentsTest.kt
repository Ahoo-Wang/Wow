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

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSValueArgument
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.wow.compiler.model.symbol.BoundedContextArguments
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.lang.reflect.Proxy

/**
 * A `@BoundedContext` is read from its annotation's arguments, never through a proxy of the annotation class: a name
 * that is a constant of another module names the context, and an argument KSP evaluated no value for fails the
 * compilation with an error naming the declaration rather than a NullPointerException.
 */
@OptIn(ExperimentalCompilerApi::class)
class BoundedContextArgumentsTest {
    private val cart = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate

        @CreateAggregate
        data class Open(val id: String)
        data class Opened(val id: String)

        @AggregateRoot
        class Cart(private val state: CartState) {
            fun onCommand(command: Open): Opened = Opened(command.id)
        }

        class CartState {
            fun onSourcing(event: Opened) {}
        }
        """.trimIndent(),
    )

    @Test
    fun `a context named by a constant of another module should be its name`() {
        val names = compileLibrary(
            SourceFile.kotlin(
                "ShopNames.kt",
                """
                package names

                object ShopNames {
                    const val CONTEXT: String = "shop"
                    const val DESCRIPTION: String = "The shop"
                }
                """.trimIndent(),
            ),
        )
        val context = SourceFile.kotlin(
            "ShopContext.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.BoundedContext
            import names.ShopNames

            @BoundedContext(name = ShopNames.CONTEXT, description = ShopNames.DESCRIPTION)
            object ShopContext
            """.trimIndent(),
        )
        val (compilation, result) = compileModelAgainst("generate", listOf(names), context, cart)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = compilation.workingDir.walkTopDown()
            .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        val shop = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java)).contexts.single()
        shop.name.assert().isEqualTo("shop")
        shop.description.assert().isEqualTo("The shop")
    }

    @Test
    fun `a context whose name has no value should fail with an error naming it, generating nothing`() {
        // Not a constant: KSP evaluates no value for it, as it may not for another module's constant.
        val context = SourceFile.kotlin(
            "ShopContext.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.BoundedContext

            object ShopNames {
                val context: String = "shop"
            }

            @BoundedContext(name = ShopNames.context)
            object ShopContext
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", context, cart)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "@BoundedContext of sample.ShopContext: KSP evaluated no value for its name or description, so nothing " +
                "of the module was generated",
        ).doesNotContain("NullPointerException")
    }

    @Test
    fun `the arguments should be read as given, a description left out being empty`() {
        BoundedContextArguments.of(annotation("name" to "shop", "description" to "The shop"))
            .assert().isEqualTo(BoundedContextArguments("shop", "The shop"))
        BoundedContextArguments.of(annotation("name" to "shop")).assert()
            .isEqualTo(BoundedContextArguments("shop", ""))
        BoundedContextArguments.of(annotation("name" to "shop", default = listOf("description" to "")))
            .assert().isEqualTo(BoundedContextArguments("shop", ""))
    }

    @Test
    fun `an argument with no value should leave the context unread`() {
        BoundedContextArguments.of(annotation("name" to null)).assert().isNull()
        BoundedContextArguments.of(annotation("description" to "The shop")).assert().isNull()
        BoundedContextArguments.of(annotation("name" to "shop", "description" to null)).assert().isNull()
    }

    /** A `KSAnnotation` with the [given] arguments and the [default] ones, by name; nothing else of it is read. */
    private fun annotation(
        vararg given: Pair<String, Any?>,
        default: List<Pair<String, Any?>> = emptyList(),
    ): KSAnnotation = fake { method ->
        when (method) {
            "getArguments" -> given.map(::argument)
            "getDefaultArguments" -> default.map(::argument)
            else -> error("A KSAnnotation's $method is not read.")
        }
    }

    private fun argument(entry: Pair<String, Any?>): KSValueArgument = fake { method ->
        when (method) {
            "getName" -> fake<KSName> { if (it == "asString") entry.first else error("A KSName's $it is not read.") }
            "getValue" -> entry.second
            else -> error("A KSValueArgument's $method is not read.")
        }
    }

    private inline fun <reified T : Any> fake(crossinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method.name)
        } as T
}
