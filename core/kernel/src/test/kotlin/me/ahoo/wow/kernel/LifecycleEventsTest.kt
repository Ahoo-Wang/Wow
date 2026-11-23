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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.AggregateDeleted
import me.ahoo.wow.api.lifecycle.AggregateRestored
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.OwnerTransferred
import me.ahoo.wow.api.lifecycle.SpaceTransferred
import me.ahoo.wow.api.schedule.ScheduleCancelled
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * A lifecycle event a decide function returns, or its scope makes, that changes nothing is no event (#6663): the same
 * answer the kernel's own lifecycle commands give. Left out of the decision, the rest of which commits; a decision left
 * with no event is unchanged.
 */
class LifecycleEventsTest {
    data class Restore(val slug: String = "")
    data class Remove(val reason: String = "")
    data class Events(val events: List<Any>, val slug: String = "")
    data class Remind(val key: String)
    data class Forget(val key: String)

    private val model = aggregate<CartState>("example", "cart") {
        initial { CartState(it.id) }
        decide<CreateCart>(Creation.REQUIRED) { _, body -> Decision.events(CartCreated(body.owner)) }
        decideSuspending<Restore>(onDeleted = true) { _, body ->
            if (body.slug.isNotEmpty() && !reserve(SLUGS, body.slug)) {
                return@decideSuspending Decision.reject("slug.taken", body.slug)
            }
            Decision.events(AggregateRestored())
        }
        decide<Remove>(onDeleted = true) { _, _ -> Decision.events(AggregateDeleted()) }
        decideSuspending<Events>(onDeleted = true) { _, body ->
            if (body.slug.isNotEmpty() && !reserve(SLUGS, body.slug)) {
                return@decideSuspending Decision.reject("slug.taken", body.slug)
            }
            Decision.events(*body.events.toTypedArray())
        }
        decide<Remind> { _, body ->
            schedule(body.key, ScheduleRule.after(30.minutes), ClearCart("late"))
            Decision.NoOp
        }
        decide<Forget> { _, body ->
            cancelSchedule(body.key)
            Decision.NoOp
        }
        decide<ClearCart> { _, _ -> Decision.NoOp }
        evolve<CartCreated> { owner = it.owner }
        evolve<ItemAdded> { items[it.productId] = it.quantity }
    }

    private val log = InMemoryEventLog()
    private val reservations = InMemoryReservationStore()
    private val kernel = Kernel(log, reservations = reservations)
    private var sequence = 0

    private suspend fun send(id: String, body: Any, requestId: String? = null): CommandOutcome {
        sequence++
        val ref = model.type.ref("tenant", id)
        return kernel.execute(model, Command("c-$sequence", requestId ?: "r-$sequence", ref, body))
    }

    private suspend fun created(id: String): CommandOutcome = send(id, CreateCart("ahoo"))

    private suspend fun deleted(id: String) {
        created(id)
        send(id, DeleteAggregate()).assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    private fun CommandOutcome.names(): List<String> = (this as CommandOutcome.Committed).stream.events.map { it.name }

    @Test
    fun `a domain function's restore of an aggregate that is not deleted should change nothing, again and again`(): Unit =
        runTest {
            created("live")
            send("live", Restore()).assert().isEqualTo(CommandOutcome.Unchanged(1))
            send("live", Restore()).assert().isEqualTo(CommandOutcome.Unchanged(1))
            deleted("gone")
            send("gone", Restore()).names().assert().containsExactly(Lifecycle.AGGREGATE_RESTORED)
        }

    @Test
    fun `a domain function's delete of an aggregate already deleted should change nothing`(): Unit = runTest {
        deleted("gone")
        send("gone", Remove()).assert().isEqualTo(CommandOutcome.Unchanged(2))
        created("live")
        send("live", Remove()).names().assert().containsExactly(Lifecycle.AGGREGATE_DELETED)
    }

    @Test
    fun `a transfer to the owner or the space the aggregate has should change nothing`(): Unit = runTest {
        created("cart")
        send("cart", Events(listOf(OwnerTransferred("bob")))).names().assert()
            .containsExactly(Lifecycle.OWNER_TRANSFERRED)
        send("cart", Events(listOf(OwnerTransferred("bob")))).assert().isEqualTo(CommandOutcome.Unchanged(2))
        send("cart", Events(listOf(SpaceTransferred("s")))).names().assert()
            .containsExactly(Lifecycle.SPACE_TRANSFERRED)
        send("cart", Events(listOf(SpaceTransferred("s")))).assert().isEqualTo(CommandOutcome.Unchanged(3))
    }

    @Test
    fun `each lifecycle event should be judged on what the events before it left, in the list's order`(): Unit =
        runTest {
            created("both")
            send("both", Events(listOf(AggregateDeleted(), AggregateRestored()))).names().assert()
                .containsExactly(Lifecycle.AGGREGATE_DELETED, Lifecycle.AGGREGATE_RESTORED)
            created("deletes")
            val deleting = send("deletes", Events(listOf(AggregateRestored(), AggregateDeleted())))
            deleting.names().assert().containsExactly(Lifecycle.AGGREGATE_DELETED)
            (deleting as CommandOutcome.Committed).deleted.assert().isTrue()
        }

    @Test
    fun `a decision should commit its other events without the lifecycle event that changes nothing, and keep its keys`(): Unit =
        runTest {
            created("mixed")
            val mixed = send("mixed", Events(listOf(ItemAdded("apple", 1), AggregateRestored()), slug = "kept"))
            mixed.names().assert().containsExactly("example.cart.ItemAdded")
            (mixed as CommandOutcome.Committed).state.let { it as CartState }.items.assert().containsEntry("apple", 1)
            created("other")
            (send("other", Restore("kept")) as CommandOutcome.Rejected).rejection.code.assert()
                .isEqualTo("slug.taken")
        }

    @Test
    fun `a decision left with no event should be unchanged and give up the keys it reserved`(): Unit = runTest {
        created("live")
        send("live", Restore("given-up")).assert().isEqualTo(CommandOutcome.Unchanged(1))
        created("other")
        send("other", Events(listOf(ItemAdded("apple", 1)), slug = "given-up")).names().assert()
            .containsExactly("example.cart.ItemAdded")
    }

    @Test
    fun `a retried request of a decision left unchanged should be answered as any unchanged retry is`(): Unit = runTest {
        created("live")
        send("live", Restore(), requestId = "restore-1").assert().isEqualTo(CommandOutcome.Unchanged(1))
        send("live", Restore(), requestId = "restore-1").assert().isEqualTo(CommandOutcome.Unchanged(1))
        send("live", ClearCart("x"), requestId = "clear-1").assert().isEqualTo(CommandOutcome.Unchanged(1))
        send("live", ClearCart("x"), requestId = "clear-1").assert().isEqualTo(CommandOutcome.Unchanged(1))
    }

    @Test
    fun `cancelling a schedule the aggregate does not keep should change nothing`(): Unit = runTest {
        created("cart")
        send("cart", Forget("nope")).assert().isEqualTo(CommandOutcome.Unchanged(1))
        send("cart", Remind("late")).names().assert().containsExactly(Schedules.SCHEDULE_SET)
        send("cart", Forget("late")).names().assert().containsExactly(Schedules.SCHEDULE_CANCELLED)
        send("cart", Forget("late")).assert().isEqualTo(CommandOutcome.Unchanged(3))
        // A function of the kernel's own that returns the event: the same answer.
        send("cart", Events(listOf(ScheduleCancelled("late")))).assert().isEqualTo(CommandOutcome.Unchanged(3))
    }

    private companion object {
        const val SLUGS = "cart.slug"
    }
}
