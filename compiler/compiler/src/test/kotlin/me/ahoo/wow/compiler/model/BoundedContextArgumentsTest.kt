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
import me.ahoo.wow.compiler.model.symbol.BoundedContextArguments
import me.ahoo.wow.manifest.Manifest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

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
        val shop = context(compilation)
        shop.name.assert().isEqualTo("shop")
        shop.description.assert().isEqualTo("The shop")
    }

    @Test
    fun `a context compiled into a library on the class path should change nothing the module generates`() {
        // Another module's domain, compiled: its context is named by its own constants.
        val ledger = compileLibrary(
            SourceFile.kotlin(
                "LedgerContext.kt",
                """
                package ledger

                import me.ahoo.wow.api.annotation.BoundedContext

                object LedgerNames {
                    const val CONTEXT: String = "ledger"
                    const val DESCRIPTION: String = "The ledger"
                }

                @BoundedContext(name = LedgerNames.CONTEXT, description = LedgerNames.DESCRIPTION)
                object LedgerContext
                """.trimIndent(),
            ),
        )
        val context = SourceFile.kotlin(
            "ShopContext.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = "shop", description = "The shop")
            object ShopContext
            """.trimIndent(),
        )
        val (domain, domainResult) = compileModelAgainst("generate", listOf(ledger), context, cart)
        domainResult.exitCode.assert().withFailMessage { domainResult.messages }
            .isEqualTo(KotlinCompilation.ExitCode.OK)
        context(domain).run {
            name.assert().isEqualTo("shop")
            aggregates.map { it.name }.assert().containsExactly("cart")
        }
        domain.workingDir.walkTopDown().any { it.name == "CartModel.kt" }.assert().isTrue()

        // A service module, named by the option, whose domain's compiled context is on its class path.
        val options = mapOf(ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop")
        val (service, serviceResult) = compileModelAgainst("generate", options, listOf(ledger), cart)
        serviceResult.exitCode.assert().withFailMessage { serviceResult.messages }
            .isEqualTo(KotlinCompilation.ExitCode.OK)
        context(service).aggregates.map { it.name }.assert().containsExactly("cart")
        service.workingDir.walkTopDown().any { it.name == "CartModel.kt" }.assert().isTrue()
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
        BoundedContextArguments.of(fakeAnnotation("name" to "shop", "description" to "The shop"))
            .assert().isEqualTo(BoundedContextArguments("shop", "The shop"))
        // KSP lists a default among the arguments, and among the default arguments; listed nowhere, it is the class's.
        val default = listOf("description" to "")
        BoundedContextArguments.of(fakeAnnotation("name" to "shop", "description" to "", default = default))
            .assert().isEqualTo(BoundedContextArguments("shop", ""))
        BoundedContextArguments.of(fakeAnnotation("name" to "shop", default = default))
            .assert().isEqualTo(BoundedContextArguments("shop", ""))
        BoundedContextArguments.of(fakeAnnotation("name" to "shop")).assert()
            .isEqualTo(BoundedContextArguments("shop", ""))
    }

    @Test
    fun `an argument with no value should leave the context unread`() {
        BoundedContextArguments.of(fakeAnnotation("name" to null)).assert().isNull()
        BoundedContextArguments.of(fakeAnnotation("description" to "The shop")).assert().isNull()
        BoundedContextArguments.of(fakeAnnotation("name" to "shop", "description" to null)).assert().isNull()
        // A given argument with no value is unread, though the annotation has a default for it.
        val default = listOf("description" to "")
        BoundedContextArguments.of(fakeAnnotation("name" to "shop", "description" to null, default = default))
            .assert().isNull()
    }

    /** The one context of the Manifest fragment [compilation] generated. */
    private fun context(compilation: KotlinCompilation) = compilation.workingDir.walkTopDown()
        .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        .let { ManifestFragmentFixtures.read(it).contexts.single() }
}
