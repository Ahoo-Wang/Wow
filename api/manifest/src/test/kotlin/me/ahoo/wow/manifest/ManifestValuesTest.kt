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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * The Manifest model is plain classes (decision D59: it may gain fields): they keep a data class's equality, hash and
 * string, and a `with` method replaces one field and keeps the others.
 */
class ManifestValuesTest {
    private val function = FunctionManifest(FunctionKind.SAGA, "shop", "CartSaga", "onCheckedOut")
    private val subscription =
        SubscriptionManifest("CartSaga.onCheckedOut", SubscriptionKind.SAGA, SubscriptionSource("shop"), function)

    @Test
    fun `the Manifest's types are values`() {
        val cart = Manifests.cart()
        valueSemantics({ Manifests.fragment() }, Manifests.fragment().copyWith(defs = emptyMap()))
        valueSemantics({ ServiceInfo("shop") }, ServiceInfo("shop", "1.0.0"))
        valueSemantics({ ContextManifest("shop") }, ContextManifest("shop", "A shop"))
        valueSemantics({ Manifests.cart() }, Manifests.cart(aggregate = "basket"))
        valueSemantics({ StateManifest("#/\$defs/S") }, StateManifest("#/\$defs/S", "f"))
        valueSemantics({ SnapshotManifest() }, SnapshotManifest(stateRevision = 2))
        valueSemantics({ QueryManifest("snapshot") }, QueryManifest("events"))
        valueSemantics({ cart.commands.first() }, cart.commands.last())
        valueSemantics({ RouteManifest("POST", "/shop/cart") }, RouteManifest("PUT", "/shop/cart"))
        valueSemantics({ ErrorManifest("shop.full", 409) }, ErrorManifest("shop.full", 409, "Full"))
        valueSemantics(
            { EventManifest("ItemAdded", schema = "#/\$defs/E") },
            EventManifest("ItemAdded", schema = "#/\$defs/F")
        )
        valueSemantics(
            { subscription },
            SubscriptionManifest("CartSaga.other", SubscriptionKind.SAGA, SubscriptionSource(), function)
        )
        valueSemantics({ JobManifest("nightly", "0 0 3 * * *") }, JobManifest("nightly", "0 0 4 * * *"))
        valueSemantics({ RetryManifest() }, RetryManifest(maxAttempts = 3))
        valueSemantics({ SubscriptionSource("shop") }, SubscriptionSource("shop", nullableState = true))
        valueSemantics({ PublishedManifest("cart", "shop.cart") }, PublishedManifest("cart", "shop.cart", false))
        valueSemantics({ function }, FunctionManifest(FunctionKind.SAGA, "shop", "CartSaga", "other"))
        valueSemantics(
            { ManifestFragment("a.json", Manifests.fragment()) },
            ManifestFragment("b.json", Manifests.fragment())
        )
        ServiceInfo("shop", "1.0.0").toString().assert().isEqualTo("ServiceInfo(name=shop, version=1.0.0)")
    }

    @Test
    fun `a with method replaces one field and keeps the others`() {
        val fragment = Manifests.fragment()
        val context = fragment.contexts.single()
        val cart = context.aggregates.first()
        fragment.copyWith(
            contexts =
            emptyList()
        ).assert().isEqualTo(Manifest(fragment.format, fragment.service, defs = fragment.defs))
        fragment.copyWith(defs = emptyMap()).contexts.assert().isEqualTo(fragment.contexts)
        context.copyWith(aggregates = emptyList()).subscriptions.assert().isEqualTo(context.subscriptions)
        val state = StateManifest("#/\$defs/Other")
        cart.copyWith(state = state).assert().isEqualTo(cart.copyWith(state = state))
        cart.copyWith(commands = emptyList()).assert().isEqualTo(cart.copyWith(commands = emptyList()))
        cart.copyWith(queries = emptyList()).assert().isEqualTo(cart.copyWith(queries = emptyList()))
        cart.state.copyWith(fingerprint = null).assert().isEqualTo(StateManifest(cart.state.schema))
        val command = cart.commands.first()
        command.copyWith(routes = emptyList()).assert().isEqualTo(command.copyWith(routes = emptyList()))
    }

    private fun valueSemantics(make: () -> Any, other: Any) {
        val value = make()
        val same = make()
        (value == value).assert().isTrue()
        (value == same).assert().isTrue()
        value.hashCode().assert().isEqualTo(same.hashCode())
        (value == other).assert().isFalse()
        (value == value.javaClass.simpleName).assert().isFalse()
        value.toString().assert().startsWith("${value.javaClass.simpleName}(").isEqualTo(same.toString())
    }
}
