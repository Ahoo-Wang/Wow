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
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * D484 ⑧: the compiler refuses a name, or a rejection code, that breaks its one rule (`NameRules`), at the
 * declaration, however the name came: written with `@Name`, or taken from a class or a function. Before, it wrote
 * every one of them into the Manifest, where the contract generators threw, guessed or wrote sources that do not
 * compile.
 */
@OptIn(ExperimentalCompilerApi::class)
class ModelNameRuleTest {
    private val context = SourceFile.kotlin(
        "OrdersContext.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.BoundedContext

        @BoundedContext(name = "orders")
        object OrdersContext
        """.trimIndent(),
    )

    private fun name(value: String?): String = value?.let { "@Name(\"$it\")" }.orEmpty()

    /** A cart whose names and codes are the ones given, ordinary ones by default. */
    @Suppress("LongParameterList")
    private fun cart(
        aggregate: String? = null,
        command: String? = null,
        event: String? = null,
        mounted: String? = null,
        commandClass: String = "OpenCart",
        localCode: String = "empty",
        fullCode: String = "orders.shared",
    ): SourceFile = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.ErrorCode
        import me.ahoo.wow.api.annotation.MountedCommand
        import me.ahoo.wow.api.annotation.Name
        import me.ahoo.wow.api.decide.Decision

        @CreateAggregate
        ${name(command)}
        data class `$commandClass`(@me.ahoo.wow.api.annotation.AggregateId val id: String)
        ${name(event)}
        data class CartOpened(val id: String)

        @MountedCommand(aggregate = "${aggregate ?: "cart"}")
        ${name(mounted)}
        data class Checkout(val id: String)

        @AggregateRoot
        ${name(aggregate)}
        class Cart {
            fun onCommand(command: `$commandClass`): Decision = Decision.events(CartOpened(command.id))
            fun onSourcing(event: CartOpened) = Unit

            object Errors {
                @ErrorCode
                const val LOCAL = "$localCode"

                @ErrorCode
                const val FULL = "$fullCode"
            }
        }
        """.trimIndent(),
    )

    /** A projection of the cart's events, its name and its handler's the ones given. */
    private fun mailer(processor: String? = null, handler: String? = null, function: String = "onOpened") =
        SourceFile.kotlin(
            "Mailer.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.Name
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection

            @Projection
            ${name(processor)}
            class Mailer {
                @OnEvent
                ${name(handler)}
                fun `$function`(event: CartOpened) = Unit
            }
            """.trimIndent(),
        )

    /** The messages of the compilation of [sources], which fails. */
    private fun refused(vararg sources: SourceFile): String {
        val (_, result) = compileModel("generate", context, *sources)
        result.exitCode.assert().withFailMessage { result.messages }
            .isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        return result.messages
    }

    private val rule = "is not a name: a name starts with a letter and holds only letters, digits, '_' and '-' " +
        "([A-Za-z][A-Za-z0-9_-]*), such as order-item; a dot separates a context, an aggregate and a type, and the " +
        "generated contracts take an identifier from every name"

    @Test
    fun `ordinary names and codes should compile`() {
        val (_, result) = compileModel(
            "generate",
            context,
            cart(aggregate = "shopping_basket", command = "Open-Cart", event = "Opened2", mounted = "checkOut"),
            mailer(processor = "mail-er", handler = "on_opened"),
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
    }

    @Test
    fun `a @Name that is no name should be refused at its declaration, with the rule and what to write`() {
        refused(cart(aggregate = "order item")).assert()
            .contains("Cart.kt:")
            .contains("@Name(\"order item\") of aggregate Cart $rule. Write a name of that form.")
        refused(cart(command = "a.b")).assert()
            .contains("@Name(\"a.b\") of command OpenCart $rule. Write a name of that form.")
        refused(cart(event = "1st")).assert()
            .contains("@Name(\"1st\") of event CartOpened $rule. Write a name of that form.")
        refused(cart(mounted = "-")).assert()
            .contains("@Name(\"-\") of mounted command Checkout $rule. Write a name of that form.")
        refused(cart(), mailer(processor = "café")).assert()
            .contains("Mailer.kt:")
            .contains("@Name(\"café\") of processor Mailer $rule. Write a name of that form.")
        refused(cart(), mailer(handler = "a/b")).assert()
            .contains("@Name(\"a/b\") of handler Mailer.onOpened $rule. Write a name of that form.")
    }

    @Test
    fun `an empty @Name should be refused wherever it stands`() {
        refused(cart(aggregate = "")).assert().contains("@Name(\"\") of aggregate Cart is not a name")
        refused(cart(command = "")).assert().contains("@Name(\"\") of command OpenCart is not a name")
        refused(cart(event = " ")).assert().contains("@Name(\" \") of event CartOpened is not a name")
        refused(cart(), mailer(processor = "")).assert().contains("@Name(\"\") of processor Mailer is not a name")
        // A handler's blank @Name keeps the message it had.
        refused(cart(), mailer(handler = "")).assert()
            .contains("handler Mailer.onOpened has a blank @Name: a handler's @Name names its subscription")
            .doesNotContain("is not a name")
    }

    @Test
    fun `a name taken from a class or a function that gives none should be refused, saying to give one with @Name`() {
        refused(cart(commandClass = "1st thing")).assert().contains(
            "command 1st thing is named \"1st thing\" after its class, which $rule. Give it a name of that form " +
                "with @Name.",
        )
        refused(cart(), mailer(function = "on opened")).assert().contains(
            "handler Mailer.on opened is named \"on opened\" after its function, which $rule. Give it a name of " +
                "that form with @Name.",
        )
        // The same class and function compile once @Name gives the name.
        val (_, named) = compileModel(
            "generate",
            context,
            cart(command = "OpenCart", commandClass = "1st thing"),
            mailer(handler = "onOpened", function = "on opened"),
        )
        named.exitCode.assert().withFailMessage { named.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
    }

    @Test
    fun `a @Name holding a quote should be refused at its declaration, where the generated model did not compile`() {
        // The regression: the name was written into a string literal of the generated model unescaped, and the build
        // failed inside CartModel.kt ("No value passed for parameter 'block'"), naming no declaration.
        val messages = refused(cart(aggregate = "a\\\"b"))
        messages.assert()
            .contains("@Name(\"a\"b\") of aggregate Cart is not a name")
            .doesNotContain("CartModel.kt")
        refused(cart(command = "a\\\"b")).assert()
            .contains("@Name(\"a\"b\") of command OpenCart is not a name")
            .doesNotContain("CartModel.kt")
    }

    @Test
    fun `a code that ends in a dot or has an empty segment should be refused at its property`() {
        val codeRule = "a code is segments joined by dots, each starting with a letter or a digit and holding only " +
            "letters, digits, '_' and '-' ([A-Za-z0-9][A-Za-z0-9_-]*), such as shop.cart.out-of-stock. Write a code " +
            "of that form."
        refused(cart(fullCode = "orders.cart.")).assert()
            .contains("Cart.kt:")
            .contains(
                "error code \"orders.cart.\" of sample.Cart.Errors.FULL is not a code: its last segment is empty " +
                    "(it ends in a dot); $codeRule",
            )
        refused(cart(fullCode = "orders..closed")).assert().contains(
            "error code \"orders..closed\" of sample.Cart.Errors.FULL is not a code: its segment 2 is empty (two " +
                "dots in a row); $codeRule",
        )
        refused(cart(fullCode = ".closed")).assert().contains(
            "error code \".closed\" of sample.Cart.Errors.FULL is not a code: its first segment is empty (it starts " +
                "with a dot); $codeRule",
        )
        refused(cart(localCode = "a b")).assert().contains(
            "error code \"a b\" of sample.Cart.Errors.LOCAL is not a code: its segment \"a b\" is not one; $codeRule",
        )
        refused(cart(localCode = "-")).assert()
            .contains("error code \"-\" of sample.Cart.Errors.LOCAL is not a code: its segment \"-\" is not one")
    }
}
