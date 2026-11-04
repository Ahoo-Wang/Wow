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

package me.ahoo.wow.kernel

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** How the kernel tells whether a command's caller reaches an aggregate, and how it resolves the policies (D390 E). */
class CommandReachTest {
    enum class Tier { GOLD, SILVER }

    class Shelf(val id: String) {
        var tier: Tier? = null
        val keepers: MutableSet<String> = linkedSetOf()
        var size: Int = 0
    }

    data class Stock(val tier: Tier)

    data class Stocked(val tier: Tier)

    private val tier = AccessAttribute.single<Shelf, Tier>("tier", Tier::class.java)
    private val keepers = AccessAttribute.multiple<Shelf, String>("keepers", String::class.java)
    private val size = AccessAttribute.single<Shelf, Int>("size", Int::class.javaObjectType)

    /** An attribute the shelf does not declare: a policy built against another version of its state. */
    private val region = AccessAttribute.single<Shelf, String>("region", String::class.java)

    private val model = aggregate<Shelf>("store", "shelf") {
        initial { Shelf(it.id) }
        decide<Stock> { _, body -> Decision.events(Stocked(body.tier)) }
        evolve<Stocked> { tier = it.tier }
        access(tier) { it.tier }
        access(keepers) { it.keepers }
        access(size) { it.size }
    }

    private val policy = accessPolicy(Shelf::class.java) {
        read { caller -> anyOf(keepers contains caller.subject, region eq caller.claim("region")) }
        write { tier eq Tier.GOLD }
    }

    private val ref = model.type.ref("acme", "s-1")
    private val codec = EventCodec()

    private fun command(caller: Caller?, causation: Causation? = null) = Command(
        "c-1",
        "r-1",
        ref,
        Stock(Tier.GOLD),
        scope = caller?.scope ?: Scope.NONE,
        causation = causation,
        caller = caller,
    )

    private fun stream(access: Map<String, Any?>?) = EventStreamRecord(
        ref = ref,
        version = 1,
        commandId = "c-0",
        requestId = "r-0",
        commandType = "store.shelf.Stock",
        commitTime = 1,
        events = listOf(EventRecord("store.shelf.Stocked", 1, "{}")),
        scope = Scope("alice"),
        access = access,
    )

    @Test
    fun `a derived command reaches the aggregate it changes, whatever it reads`() {
        val reach = CommandReach(model, policy, command(null, Causation("o-1", "orders.order/acme/o-1@1")))
        val shelf = CachedState(Shelf("s-1"), 1, Scope("alice"))
        reach.writes(shelf).assert().isTrue()
        reach.reads(shelf).assert().isTrue()
        reach.retries(stream(null), Scope("alice"), codec).assert().isTrue()
    }

    @Test
    fun `an attribute the aggregate does not declare grants nothing`() {
        val bob = Caller.of("acme", subject = "bob", attributes = mapOf("region" to listOf("east")))
        val reach = CommandReach(model, policy, command(bob))
        reach.reads(CachedState(Shelf("s-1"), 1)).assert().isFalse()
    }

    @Test
    fun `a retry under a policy reads the attributes its stream records, and an unstamped stream answers no one`() {
        val reach = CommandReach(model, policy, command(Caller.of("acme", subject = "bob")))
        reach.retries(stream(null), Scope("alice"), codec).assert().isFalse()
        val stamped = AccessValues.ofStream(
            mapOf("tier" to "GOLD", "keepers" to listOf("bob"), "size" to 3L),
            Scope("alice"),
        )
        reach.retries(stream(stamped), Scope("alice"), codec).assert().isTrue()
        val other = AccessValues.ofStream(mapOf("tier" to "GOLD", "keepers" to listOf("carol")), Scope("alice"))
        reach.retries(stream(other), Scope("alice"), codec).assert().isFalse()
    }

    @Test
    fun `a retry under a policy reads the owner and space before its stream, though the aggregate moved since`() {
        val owners = accessPolicy(Shelf::class.java) {
            read { caller -> ownerAndSpace(caller) }
            write { caller -> ownerAndSpace(caller) }
        }
        val alice = Caller.of("acme", scope = Scope("alice"), subject = "alice")
        val reach = CommandReach(model, owners, command(alice))
        // Stamped again since its commit: a later command moved the shelf to bob.
        val moved = AccessValues.ofStream(emptyMap(), Scope("bob"))
        reach.retries(stream(moved), Scope("alice"), codec).assert().isTrue()
        reach.retries(stream(moved), Scope("carol"), codec).assert().isFalse()
    }

    @Test
    fun `the stored attributes read back as the state gives them, a value of another type as none`() {
        codec.declaredOf(
            model,
            mapOf("tier" to "SILVER", "keepers" to listOf("bob", "carol"), "size" to 3L),
        ).assert().isEqualTo(mapOf("tier" to Tier.SILVER, "keepers" to setOf("bob", "carol"), "size" to 3))
        codec.declaredOf(model, mapOf("tier" to "BRONZE", "keepers" to "bob")).assert()
            .isEqualTo(mapOf("tier" to null, "keepers" to null, "size" to null))
    }

    @Test
    fun `the policies resolve to the node's models as the queries resolve them`() {
        val policies = WritePolicies.of(listOf(policy), listOf(model, cartModel))
        policies.of(model.type).assert().isSameAs(policy)
        policies.of(cartModel.type).assert().isNull()
        WritePolicies.of(emptyList(), listOf(cartModel)).assert().isSameAs(WritePolicies.NONE)
        assertThrows<IllegalStateException> { WritePolicies.of(emptyList(), listOf(model)) }
            .message.assert().contains("declares the access attributes [tier, keepers, size]")
        // A node of some of the service's contexts leaves a policy of an aggregate it does not host.
        WritePolicies.of(listOf(policy), listOf(cartModel), strict = false).assert().isSameAs(WritePolicies.NONE)
    }
}
