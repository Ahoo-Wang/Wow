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
 * Every class the processor declares carries `@WowGenerated`, which coverage tools exclude generated code by: the
 * facades of the generated top-level functions (`@file:`), the objects and classes it writes, and the object
 * expressions of the service routes. What the Kotlin compiler synthesizes inside a generated function carries none,
 * and is only ever of the two facades, which the documented patterns `*ModelKt$*` and `*SubscriptionsKt$*` name.
 */
@OptIn(ExperimentalCompilerApi::class)
class GeneratedMarksTest {
    private val shop = SourceFile.kotlin(
        "Shop.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.Access
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.BoundedContext
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.Projection
        import me.ahoo.wow.api.annotation.RouteMethod
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.annotation.ServiceRoute
        import me.ahoo.wow.api.client.CommandRequest
        import me.ahoo.wow.api.subscribe.StateEvent

        @BoundedContext(name = "shop")
        object ShopContext

        @CreateAggregate
        data class OpenCart(val owner: String)
        data class CartOpened(val owner: String)

        @AggregateRoot
        class Cart(private val state: CartState) {
            suspend fun onCommand(command: OpenCart): CartOpened = CartOpened(command.owner)
        }

        class CartState(val id: String) {
            @Access("department")
            var department: String = ""
            var owner: String = ""
                private set

            fun onSourcing(event: CartOpened) { owner = event.owner }
        }

        @Saga
        class CartSaga {
            @OnEvent
            suspend fun onOpened(event: StateEvent<CartState>): List<CommandRequest<OpenCart>> = emptyList()
        }

        @Projection
        class CartProjection {
            @OnEvent
            fun onOpened(event: StateEvent<CartState>) = Unit
        }

        data class Count(val carts: Int)

        class ShopRoutes {
            @ServiceRoute(method = RouteMethod.GET, path = "carts/count")
            suspend fun count(): Count = Count(0)
        }
        """.trimIndent(),
    )

    @Test
    fun `every class the processor declares should carry WowGenerated, and only synthetic classes of its facades none`() {
        val options = mapOf(
            ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
            ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
        )
        val (_, result) = compileModel("generate", options, shop)
        result.exitCode.assert().describedAs(result.messages).isEqualTo(KotlinCompilation.ExitCode.OK)
        // The fixture's package holds its own classes, compiled from Shop.kt, and those the processor generated.
        val generated = ClassMarks.of(result.outputDirectory)
            .filterKeys { it.name.startsWith("sample.") && it.sourceFile != "Shop.kt" }
        generated.filterValues { it }.keys.map { it.name }.assert().contains(
            "sample.CartModel",
            "sample.CartModelKt",
            "sample.CartStateAccess",
            "sample.CartStateProperties",
            "sample.CartSagaSubscriptionsKt",
            "sample.CartProjectionSubscriptionsKt",
            "sample.ShopDomainModelProvider",
        )
        // The object expression of each service route is a class of its own, which carries the mark too.
        generated.filterValues { it }.keys.map { it.name }.any { it.startsWith("sample.ShopDomainModelProvider$") }
            .assert().isTrue()
        val unmarked = generated.filterValues { !it }.keys.map { it.name }
        unmarked.assert().isNotEmpty()
        unmarked.filterNot { SYNTHETIC.matches(it) }.assert().isEmpty()
    }

    private companion object {
        /** The patterns the documentation gives for the synthetic classes of the generated facades. */
        val SYNTHETIC = Regex(""".*(ModelKt|SubscriptionsKt)\$.*""")
    }
}
