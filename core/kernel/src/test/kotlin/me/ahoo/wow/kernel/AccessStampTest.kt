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

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

/** The access attributes the kernel stamps on each commit, and how a reader of a stream takes them. */
class AccessStampTest {
    enum class Tier {
        @JsonProperty("gold")
        GOLD,
        SILVER,
    }

    class Shelf(val id: String) {
        var tier: Tier? = null
        val keepers: MutableSet<String> = linkedSetOf()
        var size: Int = 0
    }

    data class Stock(val tier: Tier, val keepers: List<String>, val size: Int)

    data class Stocked(val tier: Tier, val keepers: List<String>, val size: Int)

    private val tier = AccessAttribute.single<Shelf, Tier>("tier", Tier::class.java)
    private val keepers = AccessAttribute.multiple<Shelf, String>("keepers", String::class.java)
    private val size = AccessAttribute.single<Shelf, Int>("size", Int::class.javaObjectType)

    private val model = aggregate<Shelf>("store", "shelf") {
        initial { Shelf(it.id) }
        decide<Stock>(
            Creation.ALLOWED
        ) { _, command -> Decision.events(Stocked(command.tier, command.keepers, command.size)) }
        evolve<Stocked> {
            tier = it.tier
            keepers += it.keepers
            size = it.size
        }
        access(tier) { it.tier }
        access(keepers) { it.keepers }
        access(size) { it.size }
    }

    private val codec = EventCodec()

    @Test
    fun `the stored form names an enum as its JSON does, sorts a multiple one and widens an integer`() {
        val shelf = Shelf("s-1").apply {
            tier = Tier.GOLD
            keepers += listOf("carol", "bob")
            size = 3
        }
        codec.accessOf(model, shelf).assert()
            .isEqualTo(mapOf("tier" to "gold", "keepers" to listOf("bob", "carol"), "size" to 3L))
        codec.accessOf(model, Shelf("s-2")).assert()
            .isEqualTo(mapOf("tier" to null, "keepers" to emptyList<String>(), "size" to 0L))
        codec.accessOf(cartModel, CartState("c-1")).assert().isEmpty()
    }

    @Test
    fun `a commit records the attributes after it, with the owner and the space of its scope`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        val kernel = Kernel(log, codec)
        val ref = model.type.ref("tenant", "s-1")
        val command = Command("c-1", "r-1", ref, Stock(Tier.SILVER, listOf("dave"), 2), scope = Scope("alice", "aisle"))
        kernel.execute(model, command)
        log.read(ref).toList().single().access.assert().isEqualTo(
            mapOf(
                "owner" to "alice",
                "space" to "aisle",
                "tier" to "SILVER",
                "keepers" to listOf("dave"),
                "size" to 2L
            ),
        )
    }

    @Test
    fun `a reader takes a stream's own attributes when they are the model's, else the state's`() {
        val state = codec.stateText(Shelf("s-1").apply { tier = Tier.GOLD })
        val current =
            mapOf("owner" to "alice", "space" to null, "tier" to "SILVER", "keepers" to listOf("x"), "size" to 1L)
        codec.accessAfter(model, stream(current), state).assert()
            .isEqualTo(mapOf("tier" to "SILVER", "keepers" to listOf("x"), "size" to 1L))
        val fromState = mapOf("tier" to "gold", "keepers" to emptyList<String>(), "size" to 0L)
        // Stamped before the model declared what it declares now, or not stamped at all.
        codec.accessAfter(model, stream(mapOf("owner" to "alice", "space" to null, "tier" to "SILVER")), state)
            .assert().isEqualTo(fromState)
        codec.accessAfter(model, stream(null), state).assert().isEqualTo(fromState)
        codec.accessAfter(cartModel, stream(null), codec.stateText(CartState("c-1"))).assert().isEmpty()
    }

    private fun stream(access: Map<String, Any?>?) = EventStreamRecord(
        ref = model.type.ref("tenant", "s-1"),
        version = 1,
        commandId = "c-1",
        requestId = "r-1",
        commandType = "store.shelf.Stock",
        commitTime = 1,
        events = listOf(EventRecord("store.shelf.Stocked", 1, "{}")),
        scope = Scope("alice"),
        access = access,
    )
}
