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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/** The sources the fragment tests compile, a bounded context and a cart, and the reading of the fragments written. */
@OptIn(ExperimentalCompilerApi::class)
internal object ManifestFragmentFixtures {
    val context = SourceFile.kotlin(
        "ShopContext.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.BoundedContext

        @BoundedContext(name = "shop", description = "The shop")
        object ShopContext
        """.trimIndent(),
    )

    val cart = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AfterCommand
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.Alias
        import me.ahoo.wow.api.annotation.AllowCreate
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.Description
        import me.ahoo.wow.api.annotation.Name
        import me.ahoo.wow.api.annotation.OnSourcing
        import me.ahoo.wow.api.annotation.Revision
        import me.ahoo.wow.api.annotation.Upgrade
        import me.ahoo.wow.api.decide.Decision
        import tools.jackson.databind.JsonNode

        @CreateAggregate
        data class CreateCart(val owner: String)
        @Name("PutItem")
        @Alias("AddItem")
        data class AddItem(val productId: String, val quantity: Int)
        @AllowCreate
        data class Checkout(val note: String)
        data class Inspect(val note: String)
        class Commands {
            data class Clear(val all: Boolean)
        }

        data class CartCreated(val owner: String)
        @Name("ItemPut")
        @Revision(2)
        @Alias("ItemAdded", "shop.basket.ItemAdded")
        data class ItemAdded(val productId: String, val quantity: Int)
        @Upgrade(event = ItemAdded::class, from = 1)
        fun itemAddedFrom1(node: JsonNode): JsonNode = node
        sealed interface CheckoutEvent
        data class CheckedOut(val total: Long) : CheckoutEvent
        data class CheckoutFailed(val reason: String) : CheckoutEvent
        data class Audited(val command: String)

        @AggregateRoot
        @Description("A shopping cart")
        class Cart(private val state: CartState) {
            fun onCommand(command: CreateCart): CartCreated = CartCreated(command.owner)

            fun onCommand(command: AddItem): Any =
                if (command.quantity < 1) Decision.reject("cart.empty", "empty") else ItemAdded(command.productId, command.quantity)

            fun onCommand(command: Checkout): CheckoutEvent = CheckedOut(state.items.size.toLong())

            fun onCommand(command: Commands.Clear): List<ItemAdded> = emptyList()

            suspend fun onCommand(command: Inspect) = Unit

            @AfterCommand(include = [Checkout::class, Inspect::class])
            fun afterCommand(command: Any): Audited = Audited(command.javaClass.simpleName)
        }

        class CartState(val id: String) {
            val items = mutableMapOf<String, Int>()

            @OnSourcing
            fun onCreated(event: CartCreated) = Unit

            fun onSourcing(event: ItemAdded) { items[event.productId] = event.quantity }

            fun onSourcing(event: CheckedOut) = Unit

            fun onSourcing(event: CheckoutFailed) = Unit

            fun onSourcing(event: Audited) = Unit
        }
        """.trimIndent(),
    )

    fun fragments(compilation: KotlinCompilation): List<File> =
        compilation.workingDir.walkTopDown()
            .filter { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
            .toList()

    fun read(file: File): Manifest = ManifestTree.read(
        jacksonObjectMapper().readValue(file, Map::class.java),
        ManifestTree.REFUSE
    )
}
