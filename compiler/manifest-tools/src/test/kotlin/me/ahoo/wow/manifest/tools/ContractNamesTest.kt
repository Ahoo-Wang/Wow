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

package me.ahoo.wow.manifest.tools

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.SnapshotManifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * D484 ⑧: the contract generator refuses a Manifest that holds a name or a code the Wow compiler would refuse, naming
 * the entry. Before, a code ending in a dot ended the generation with `NoSuchElementException: Char sequence is
 * empty.`, and an aggregate `1st` or `a/b` was written as an object that does not compile.
 */
class ContractNamesTest {
    private val notCompiled =
        "The Wow compiler refuses it, so this Manifest did not come from one: correct what wrote it."

    @Suppress("LongParameterList")
    private fun context(
        aggregate: String = "order",
        command: String = "PlaceOrder",
        event: String = "OrderPlaced",
        job: String = "wow.schedule.EnableJob",
        subscription: String = "OrderSaga.onPlaced",
        code: String = "shop.order.wrong-status",
    ) = ContextManifest(
        "shop",
        aggregates = listOf(
            AggregateManifest(
                name = aggregate,
                state = StateManifest(Manifest.schemaRef("shop.OrderState")),
                snapshot = SnapshotManifest(enabled = true),
                commands = listOf(
                    CommandManifest(command, schema = Manifest.schemaRef("shop.PlaceOrder")),
                    CommandManifest("wow.lifecycle.DeleteAggregate", schema = "x", system = true),
                ),
                events = listOf(EventManifest(event, schema = Manifest.schemaRef("shop.OrderPlaced"))),
                queries = emptyList(),
                ownership = Ownership.TENANT,
            ),
        ),
        subscriptions = listOf(
            SubscriptionManifest(
                name = subscription,
                kind = SubscriptionKind.SAGA,
                source = SubscriptionSource(context = "shop", aggregates = listOf("order")),
                function = FunctionManifest(FunctionKind.SAGA, "shop", "OrderSaga", "onPlaced"),
            ),
        ),
        errors = listOf(ErrorManifest(code, 422)),
        commands = listOf(CommandManifest(job, schema = "x", system = true)),
    )

    private fun generate(context: ContextManifest): String =
        ContractSources.of(context, emptyMap(), mapOf("shop" to "shop.api"), queryFields = false).text

    /** The message the generation of [context] is refused with. */
    private fun refusal(context: ContextManifest): String =
        assertThrows<IllegalStateException> { generate(context) }.message!!

    @Test
    fun `a Manifest of names and codes the compiler accepts, the framework's own among them, should generate`() {
        generate(context()).assert().contains(
            "public object Order {",
            "public const val PLACE_ORDER: kotlin.String = \"shop.order.PlaceOrder\"",
            "public const val WRONG_STATUS: kotlin.String = \"shop.order.wrong-status\"",
            "ORDER_SAGA__ON_PLACED",
        )
    }

    @Test
    fun `a code that ends in a dot should be refused naming it, where the generation threw NoSuchElementException`() {
        // The regression: `errorName` called `first()` on the empty constant of what follows the last dot.
        refusal(context(code = "shop.order.")).assert().isEqualTo(
            "The error code \"shop.order.\" of the context shop is not a code: its last segment is empty (it ends " +
                "in a dot); a code is segments joined by dots, each starting with a letter or a digit and holding " +
                "only letters, digits, '_' and '-' ([A-Za-z0-9][A-Za-z0-9_-]*), such as shop.cart.out-of-stock. " +
                notCompiled,
        )
        refusal(context(code = "shop.")).assert().contains("The error code \"shop.\" of the context shop is not a code")
        refusal(context(code = "shop..closed")).assert().contains("its segment 2 is empty (two dots in a row)")
        refusal(context(code = "shop.order.-")).assert().contains("its segment \"-\" is not one")
    }

    @Test
    fun `an aggregate no object can be named after should be refused naming it, where its object did not compile`() {
        listOf("1st", "a/b", "", "-", "a.b", "order item").forEach { name ->
            refusal(context(aggregate = name)).assert().describedAs(name).isEqualTo(
                "The aggregate \"$name\" of the context shop is not a name: a name starts with a letter and holds " +
                    "only letters, digits, '_' and '-' ([A-Za-z][A-Za-z0-9_-]*), such as order-item; a dot " +
                    "separates a context, an aggregate and a type, and the generated contracts take an identifier " +
                    "from every name. $notCompiled",
            )
        }
    }

    @Test
    fun `a command, an event or a subscription that is no name should be refused naming it and its owner`() {
        // An empty command name, and an empty part of a subscription's, threw NoSuchElementException too.
        refusal(context(command = "")).assert()
            .contains("The command \"\" of the aggregate order of the context shop is not a name")
        refusal(context(command = "shop.order.PlaceOrder")).assert()
            .contains("The command \"shop.order.PlaceOrder\" of the aggregate order of the context shop is not a name")
        refusal(context(event = "Order Placed")).assert()
            .contains("The event \"Order Placed\" of the aggregate order of the context shop is not a name")
        refusal(context(job = "Tick!")).assert().contains("The command \"Tick!\" of the context shop is not a name")
        listOf("", "...", "OrderSaga.", "OrderSaga.on placed").forEach { name ->
            refusal(context(subscription = name)).assert().describedAs(name).contains(
                "The subscription \"$name\" of the context shop, a processor's name and a handler's joined by a " +
                    "dot, is not a name",
            )
        }
    }
}
