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
package me.ahoo.wow.api.naming

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.subscribe.ExecutionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The identity texts of decision D408: one escaping rule, a ref's text that reads back as the ref, and texts that
 * name one aggregate or execution each, while an ordinary name reads as it is.
 */
class IdentityTextTest {
    /** Every non-blank string of up to [length] tokens: the separators, '%', digits and non-ASCII among them. */
    private fun names(length: Int): List<String> =
        (1..length).fold(listOf("")) { shorter, _ -> shorter + shorter.flatMap { s -> TOKENS.map { s + it } } }
            .distinct().filter { it.isNotBlank() }

    private val types = listOf(AggregateType("shop", "order"), AggregateType("a.b", "c"), AggregateType("a", "b.c"))

    private val refs: List<AggregateRef> by lazy {
        val names = names(2)
        types.flatMap { type -> names.flatMap { tenant -> names.map { id -> type.ref(tenant, id) } } }
    }

    @Test
    fun `an escaped text unescapes to itself, and a text holding nothing reserved is not copied`() {
        names(3).forEach { name ->
            Escapes.unescaped(Escapes.escaped(name, "%/:@")).assert().isEqualTo(name)
        }
        val plain = "order-1"
        Escapes.escaped(plain, "%/").assert().isSameAs(plain)
        Escapes.escaped("a/b%c", "%/").assert().isEqualTo("a%2Fb%25c")
        listOf("%", "%2", "%2f", "%G0", "a%").forEach { Escapes.unescaped(it).assert().isNull() }
    }

    @Test
    fun `a ref's text reads back as the ref, and no two refs share one`() {
        refs.forEach { ref -> AggregateRef.parse(ref.toString()).assert().isEqualTo(ref) }
        refs.map { it.toString() }.toSet().size.assert().isEqualTo(refs.size)
    }

    @Test
    fun `a ref whose names hold nothing reserved reads byte for byte as before`() {
        AggregateType("shop", "order").ref("tenant", "o-1").toString().assert().isEqualTo("shop.order/tenant/o-1")
        AggregateType("shop", "order").ref("(0)", "订单-1@x:y").toString().assert()
            .isEqualTo("shop.order/(0)/订单-1@x:y")
        AggregateType("shop", "order").unassigned("t").toString().assert().isEqualTo("shop.order/t/(none)")
        ExecutionId(
            "example",
            "cart-projection.onEvent",
            AggregateType("example", "cart").ref("tenant", "c-1"),
            3
        ).toString()
            .assert().isEqualTo("example.cart-projection.onEvent:example.cart/tenant/c-1@3")
    }

    @Test
    fun `a separator in a name is escaped, so the refs it joined apart stay apart`() {
        val type = AggregateType("shop", "order")
        type.ref("a/b", "c").toString().assert().isEqualTo("shop.order/a%2Fb/c")
        type.ref("a", "b/c").toString().assert().isEqualTo("shop.order/a/b%2Fc")
        type.ref("t", "50%").toString().assert().isEqualTo("shop.order/t/50%25")
        AggregateType("a.b", "c").ref("t", "i").toString().assert().isEqualTo("a%2Eb.c/t/i")
    }

    @Test
    fun `no two executions share a text`() {
        val subscriptions = names(2)
        val executions = subscriptions.flatMap { subscription ->
            refs.take(400).flatMap { ref -> listOf(1, 12).map { ExecutionId(ref.type.context, subscription, ref, it) } }
        }
        executions.map { it.toString() }.toSet().size.assert().isEqualTo(executions.toSet().size)
        ExecutionId("x", "s:t", AggregateType("x", "y").ref("t", "i"), 1).toString().assert()
            .isEqualTo("x.s%3At:x.y/t/i@1")
    }

    @Test
    fun `a text no ref writes parses to nothing`() {
        listOf(
            "o-1",
            "shop.order/t",
            "shop.order/t/i/j",
            "shoporder/t/i",
            ".order/t/i",
            "%zz.order/t/i",
            "shop.%zz/t/i",
            "shop.order/%zz/i",
            "shop.order/t/%zz",
            "shop.order/t/%61",
            "shop.order/ /i",
        ).forEach { AggregateRef.parse(it).assert().isNull() }
    }

    private companion object {
        val TOKENS = listOf("a", "/", "%", "@", ":", ".", "1", "é", "中")
    }

    @Test
    fun `two contexts' subscriptions of one name execute under two texts, as their invocation keys`() {
        val ref = AggregateType("shop", "order").ref("t", "o-1")
        val billing = ExecutionId("billing", "Audit.onPlaced", ref, 2)
        val shipping = ExecutionId("shipping", "Audit.onPlaced", ref, 2)
        billing.assert().isNotEqualTo(shipping)
        billing.toString().assert().isEqualTo("billing.Audit.onPlaced:shop.order/t/o-1@2")
        shipping.toString().assert().isNotEqualTo(billing.toString())
        // A context that held a '.', ':' or '%' could make two texts one: it is refused, as no bounded context holds one.
        listOf("bill.ing", "bill:ing", "bill%3Aing", " ").forEach { context ->
            assertThrows<IllegalArgumentException> { ExecutionId(context, "Audit.onPlaced", ref, 2) }
        }
    }
}
