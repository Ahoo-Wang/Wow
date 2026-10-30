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

package me.ahoo.wow.tck.timer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The [TimerStore] contract every backend passes. A backend's test
 * extends it and supplies, from [createStore], a store of the bounded context [context] and a log of [logPartitions]
 * partitions that holds no timer yet: a new context per test where the storage is shared, since the due read takes
 * every aggregate type of the context. Each call gives a store over the same storage, as another node's. Each timer is
 * in its owner's partition.
 */
abstract class TimerStoreSpec {
    protected abstract fun createStore(): TimerStore

    /** The context of the store [createStore] gives, and so of the aggregates owning its timers. */
    protected open val context: String = "shop"

    /** The partitions of the context's log, which a timer's partition is its owner's among. */
    protected open val logPartitions: Int = 64

    private val store: TimerStore by lazy { createStore() }

    private val run = UUID.randomUUID().toString()
    private val owner: AggregateRef by lazy { ownerOf("tenant", "o-$run") }
    private val target = AggregateType("shop", "order").ref("tenant", "o-$run")

    /** The partition of [owner]'s timers. */
    private val partition by lazy { Partitioner.partitionOf(owner, logPartitions) }
    private val partitions by lazy { setOf(partition) }

    /** An owner of this test's, of [type], whose partition is none of [taken]'s. */
    private fun ownerOutside(taken: Set<Int>, type: String = "order"): AggregateRef =
        generateSequence(1) { it + 1 }.map { AggregateType(context, type).ref("tenant", "o-$run-$it") }
            .first { Partitioner.partitionOf(it, logPartitions) !in taken }

    private fun partitionOf(owner: AggregateRef): Int = Partitioner.partitionOf(owner, logPartitions)

    private fun timer(
        key: String = "pay",
        version: Long = 1,
        dueAt: Long = DUE,
        owner: AggregateRef = this.owner,
        target: AggregateRef = this.target,
        partition: Int = Partitioner.partitionOf(owner, logPartitions),
        rule: ScheduleRule = ScheduleRule.At(dueAt),
    ) = Timer(
        id = "$owner#$key@$version",
        owner = owner,
        key = key,
        version = version,
        partition = partition,
        rule = rule,
        dueAt = dueAt,
        target = target,
        commandType = "shop.order.CancelOrder",
        body = """{"reason":"unpaid","amount":2.50}""",
        causation = Causation("command-1", "$owner@$version"),
        headers = mapOf("app" to "web"),
        operator = Operator("alice", "tenant", crossScope = true),
        scope = Scope("alice", "space-a"),
    )

    private suspend fun settle(loaded: Timer, next: Timer): Boolean = store.settle(listOf(TimerChange(loaded, next))) == 1

    private fun Timer.fired(outcome: String = TimerOutcomes.COMMITTED) =
        copyWith(status = TimerStatus.FIRED, attempts = attempts + 1, outcome = outcome)

    @Test
    fun `a timer put is found and due once its time comes`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer).assert().isTrue()
        store.find(owner, "pay").assert().isEqualTo(timer)
        store.find(owner, "other").assert().isNull()
        store.due(partitions, DUE - 1, LIMIT).assert().isEmpty()
        store.due(partitions, DUE, LIMIT).assert().containsExactly(timer)
        store.due(emptySet(), DUE, LIMIT).assert().isEmpty()
    }

    @Test
    fun `a context with no timer type yet reads no timer, and finds the first type another node adds`(): Unit =
        runBlocking {
            // Most contexts never schedule: their timer loop reads on every poll, and each read must just find nothing.
            val all = (0 until logPartitions).toSet()
            store.due(all, DUE, LIMIT).assert().isEmpty()
            store.parked(target).assert().isEmpty()
            val timer = timer()
            createStore().put(timer).assert().isTrue()
            store.due(all, DUE, LIMIT).assert().containsExactly(timer)
        }

    @Test
    fun `a timer's rule and slot read back as written, whatever the rule`(): Unit = runBlocking {
        val after = timer(key = "after", rule = ScheduleRule.After(60_000)).copyWith(slot = 3)
        val cron = timer(key = "cron", rule = ScheduleRule.Cron("0 0 9 * * MON-FRI", "Asia/Shanghai", Misfire.CATCH_UP))
        val utc = timer(key = "utc", rule = ScheduleRule.cron("0 */5 * * * *"))
        listOf(after, cron, utc).forEach { store.put(it).assert().isTrue() }
        store.find(owner, "after").assert().isEqualTo(after)
        store.find(owner, "cron").assert().isEqualTo(cron)
        store.find(owner, "utc").assert().isEqualTo(utc)
        after.requestId.assert().isEqualTo("${after.id}#3")
    }

    @Test
    fun `a timer without causation, headers, operator or scope is found as put`(): Unit = runBlocking {
        val timer = timer(key = "bare")
            .copyWith(causation = null, headers = emptyMap(), operator = null, scope = Scope.NONE)
        store.put(timer).assert().isTrue()
        store.find(owner, "bare").assert().isEqualTo(timer)
        val spaced = timer(key = "spaced").copyWith(scope = Scope(spaceId = "space-a"))
        store.put(spaced).assert().isTrue()
        store.find(owner, "spaced").assert().isEqualTo(spaced)
    }

    @Test
    fun `due timers of every partition asked come earliest first, within the limit`(): Unit = runBlocking {
        val second = ownerOutside(setOf(partition))
        val third = ownerOutside(setOf(partition, partitionOf(second)))
        val elsewhereOwner = ownerOutside(setOf(partition, partitionOf(second), partitionOf(third)))
        val late = timer(key = "late", dueAt = DUE + 3)
        val early = timer(key = "early", dueAt = DUE)
        val middle = timer(key = "middle", dueAt = DUE + 1, owner = second)
        val thirdTimer = timer(key = "third", dueAt = DUE + 2, owner = third)
        val elsewhere = timer(key = "elsewhere", owner = elsewhereOwner)
        listOf(late, early, middle, thirdTimer, elsewhere).forEach { store.put(it) }
        val asked = setOf(partition, partitionOf(second), partitionOf(third))
        store.due(asked, DUE + 10, LIMIT).assert().containsExactly(early, middle, thirdTimer, late)
        store.due(asked, DUE + 10, 2).assert().containsExactly(early, middle)
        store.due(asked, DUE + 1, LIMIT).assert().containsExactly(early, middle)
        store.due(setOf(partitionOf(second)), DUE + 10, LIMIT).assert().containsExactly(middle)
        store.due(partitions, DUE + 10, LIMIT).assert().containsExactly(early, late)
    }

    @Test
    fun `due timers of one time come by id`(): Unit = runBlocking {
        val b = timer(key = "b")
        val a = timer(key = "a", owner = ownerOutside(setOf(partition)))
        listOf(b, a).forEach { store.put(it) }
        store.due(setOf(partition, a.partition), DUE, LIMIT).map { it.key }.assert()
            .containsExactly(*listOf(a, b).sortedBy { it.id }.map { it.key }.toTypedArray())
    }

    @Test
    fun `the timers of every aggregate type of the context are due together, earliest first`(): Unit = runBlocking {
        // Each type's timers are kept apart, and the due read takes them all.
        val invoice = generateSequence(
            1
        ) { it + 1 }.map { AggregateType(context, "invoice").ref("tenant", "i-$run-$it") }
            .first { partitionOf(it) == partition }
        val shipment = generateSequence(1) { it + 1 }
            .map { AggregateType(context, "shipment").ref("tenant", "s-$run-$it") }
            .first { partitionOf(it) == partition }
        val order = timer(key = "pay", dueAt = DUE + 2)
        val invoiced = timer(key = "pay", dueAt = DUE, owner = invoice)
        val shipped = timer(key = "pay", dueAt = DUE + 1, owner = shipment)
        listOf(order, invoiced, shipped).forEach { store.put(it).assert().isTrue() }
        store.due(partitions, DUE + 10, LIMIT).assert().containsExactly(invoiced, shipped, order)
        store.due(partitions, DUE + 10, 2).assert().containsExactly(invoiced, shipped)
        // An owner of another type with the same tenant and id keeps its own timers.
        val sameId = AggregateType(context, "invoice").ref(owner.tenantId, owner.id)
        store.find(sameId, "pay").assert().isNull()
        store.cancelAll(sameId, 9).assert().isZero()
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.PENDING)
        store.settle(listOf(TimerChange(order, order.fired()), TimerChange(invoiced, invoiced.fired())))
            .assert().isEqualTo(2)
        store.due(partitions, DUE + 10, LIMIT).assert().containsExactly(shipped)
        // A target's parked timers are found whichever type owns them.
        val parked = shipped.copyWith(status = TimerStatus.PARKED, attempts = 3, outcome = "down")
        settle(shipped, parked).assert().isTrue()
        store.parked(target).assert().containsExactly(parked)
    }

    @Test
    fun `a store finds the timers of a type another store of the context wrote first`(): Unit = runBlocking {
        // A type's timers another node wrote first are due here at once, on the next read.
        val invoice = AggregateType(context, "invoice").ref("tenant", "i-$run")
        val invoiced = timer(key = "pay", owner = invoice)
        store.put(invoiced).assert().isTrue()
        val asked = setOf(partition, invoiced.partition)
        store.due(asked, DUE + 10, LIMIT).assert().containsExactly(invoiced)
        val order = timer(key = "pay", dueAt = DUE + 1)
        createStore().put(order).assert().isTrue()
        store.due(asked, DUE + 10, LIMIT).assert().containsExactly(invoiced, order)
        createStore().due(asked, DUE + 10, LIMIT).assert().containsExactly(invoiced, order)
    }

    @Test
    fun `putting the same timer again changes nothing, and never revives it`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer).assert().isTrue()
        store.put(timer).assert().isFalse()
        settle(timer, timer.fired()).assert().isTrue()
        store.put(timer).assert().isFalse()
        store.find(owner, "pay").assert().isEqualTo(timer.fired())
        store.due(partitions, DUE, LIMIT).assert().isEmpty()
    }

    @Test
    fun `a later version reschedules, an earlier one is ignored`(): Unit = runBlocking {
        val first = timer(version = 1)
        val second = timer(version = 3, dueAt = DUE + 100)
        store.put(first)
        settle(first, first.fired(TimerOutcomes.REJECTED))
        store.put(second).assert().isTrue()
        store.find(owner, "pay").assert().isEqualTo(second)
        store.put(timer(version = 2)).assert().isFalse()
        store.put(first).assert().isFalse()
        store.find(owner, "pay").assert().isEqualTo(second)
        store.due(partitions, DUE + 100, LIMIT).assert().containsExactly(second)
    }

    @Test
    fun `a cancel takes a pending timer held at its version or earlier`(): Unit = runBlocking {
        store.put(timer(version = 2))
        store.cancel(owner, "pay", 1).assert().isFalse()
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.PENDING)
        store.cancel(owner, "pay", 3).assert().isTrue()
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        store.cancel(owner, "pay", 3).assert().isFalse()
        store.cancel(owner, "missing", 3).assert().isFalse()
        store.due(partitions, DUE, LIMIT).assert().isEmpty()
        // A replay of the cancelled timer's put does not revive it.
        store.put(timer(version = 2)).assert().isFalse()
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
    }

    @Test
    fun `a cancel leaves a fired timer as it is`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer)
        settle(timer, timer.fired())
        store.cancel(owner, "pay", 2).assert().isFalse()
        store.find(owner, "pay").assert().isEqualTo(timer.fired())
    }

    @Test
    fun `cancelling all of an owner's timers takes the pending ones only`(): Unit = runBlocking {
        val pay = timer(key = "pay")
        val remind = timer(key = "remind", version = 2)
        val fired = timer(key = "fired")
        val later = timer(key = "later", version = 9)
        val other = ownerOf("tenant", "o-$run-other")
        val others = timer(owner = other)
        listOf(pay, remind, fired, later, others).forEach { store.put(it) }
        settle(fired, fired.fired())
        store.cancelAll(owner, 5).assert().isEqualTo(2)
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        store.find(owner, "remind")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        store.find(owner, "fired")?.status.assert().isEqualTo(TimerStatus.FIRED)
        store.find(owner, "later")?.status.assert().isEqualTo(TimerStatus.PENDING)
        store.find(other, "pay")?.status.assert().isEqualTo(TimerStatus.PENDING)
    }

    @Test
    fun `owners whose tenant and id join alike keep their timers apart`(): Unit = runBlocking {
        // `t` / `x/y` and `t/x` / `y` both join to `context.order/t/x/y`.
        val slashedId = ownerOf("t-$run", "x/y")
        val slashedTenant = ownerOf("t-$run/x", "y")
        val first = timer(owner = slashedId, version = 2)
        val second = timer(owner = slashedTenant, version = 1)
        store.put(first).assert().isTrue()
        store.put(second).assert().isTrue()
        store.find(slashedId, "pay").assert().isEqualTo(first)
        store.find(slashedTenant, "pay").assert().isEqualTo(second)
        store.cancel(slashedTenant, "pay", 1).assert().isTrue()
        store.find(slashedId, "pay")?.status.assert().isEqualTo(TimerStatus.PENDING)
        store.put(timer(owner = slashedTenant, key = "remind")).assert().isTrue()
        store.cancelAll(slashedTenant, 9).assert().isEqualTo(1)
        store.find(slashedId, "pay")?.status.assert().isEqualTo(TimerStatus.PENDING)
        settle(first, first.fired()).assert().isTrue()
        store.find(slashedTenant, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
    }

    private fun ownerOf(tenant: String, id: String): AggregateRef = AggregateType(context, "order").ref(tenant, id)

    @Test
    fun `a change is written only while the timer is as it was loaded`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer)
        val retried = timer.copyWith(attempts = 1, dueAt = DUE + 5, outcome = "java.io.IOException: down")
        settle(timer.copyWith(status = TimerStatus.FIRED), retried).assert().isFalse()
        settle(timer, retried).assert().isTrue()
        store.find(owner, "pay").assert().isEqualTo(retried)
        // The row moved on: a change from what was loaded before does nothing.
        settle(timer, timer.fired()).assert().isFalse()
        store.due(partitions, DUE, LIMIT).assert().isEmpty()
        store.due(partitions, DUE + 5, LIMIT).assert().containsExactly(retried)
        val rescheduled = timer(version = 2)
        store.put(rescheduled)
        settle(retried, retried.fired()).assert().isFalse()
        store.find(owner, "pay").assert().isEqualTo(rescheduled)
        settle(timer(key = "missing"), timer(key = "missing").fired()).assert().isFalse()
        store.settle(emptyList()).assert().isZero()
    }

    @Test
    fun `firing a slot moves the timer to its next slot in one write`(): Unit = runBlocking {
        val timer = timer(rule = ScheduleRule.cron("0 0 * * * *"))
        store.put(timer)
        val next = timer.copyWith(
            slot = 2,
            dueAt = DUE + HOUR,
            slotDueAt = DUE + HOUR,
            attempts = 0,
            outcome = TimerOutcomes.COMMITTED,
        )
        settle(timer, next).assert().isTrue()
        store.find(owner, "pay").assert().isEqualTo(next)
        next.context.due.assert().isEqualTo(DUE + HOUR)
        next.requestId.assert().isEqualTo("${timer.id}#2")
        store.due(partitions, DUE, LIMIT).assert().isEmpty()
        store.due(partitions, DUE + HOUR, LIMIT).assert().containsExactly(next)
        // The slot fired already cannot be fired again.
        settle(timer, timer.copyWith(slot = 2, dueAt = DUE + 2 * HOUR)).assert().isFalse()
        store.find(owner, "pay").assert().isEqualTo(next)
    }

    @Test
    fun `a timer's slot keeps its due time through claims and retries`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer)
        val claimed = timer.claimed()
        settle(timer, claimed).assert().isTrue()
        // A technical failure: due again after the backoff, its slot still due when it was.
        val retried = claimed.copyWith(
            status = TimerStatus.PENDING,
            attempts = 1,
            dueAt = DUE + CLAIM + 5,
            outcome = "down"
        )
        settle(claimed, retried).assert().isTrue()
        val loaded = store.due(partitions, DUE + CLAIM + 5, LIMIT).single()
        loaded.assert().isEqualTo(retried)
        loaded.slotDueAt.assert().isEqualTo(DUE)
        loaded.context.due.assert().isEqualTo(DUE)
        // Claimed again, and that claim lapsing: the next owner reads the slot's due time as it was.
        val reclaimed = loaded.claimed(DUE + 2 * CLAIM)
        settle(loaded, reclaimed).assert().isTrue()
        val lapsed = store.due(partitions, DUE + 2 * CLAIM, LIMIT).single()
        lapsed.slotDueAt.assert().isEqualTo(DUE)
        lapsed.dueAt.assert().isEqualTo(DUE + 2 * CLAIM)
        // A change from another due time of the slot does nothing.
        settle(lapsed.copyWith(slotDueAt = DUE + 1), lapsed.fired()).assert().isFalse()
        settle(lapsed, lapsed.fired()).assert().isTrue()
        store.find(owner, "pay")?.slotDueAt.assert().isEqualTo(DUE)
    }

    private fun Timer.claimed(deadline: Long = DUE + CLAIM) = copyWith(status = TimerStatus.FIRING, dueAt = deadline)

    @Test
    fun `a monthly timer advances slot after slot, each written from the claim of the one before`(): Unit =
        runBlocking {
            val timer = timer(key = "invoice", dueAt = FEBRUARY, rule = MONTHLY)
            store.put(timer)
            var loaded = store.due(partitions, FEBRUARY, LIMIT).single()
            listOf(MARCH, APRIL, MAY).forEachIndexed { index, nextDue ->
                val claimed = loaded.claimed(loaded.slotDueAt + CLAIM)
                settle(loaded, claimed).assert().isTrue()
                val advanced = claimed.copyWith(
                    status = TimerStatus.PENDING,
                    slot = claimed.slot + 1,
                    slotDueAt = nextDue,
                    dueAt = nextDue,
                    attempts = 0,
                    outcome = TimerOutcomes.COMMITTED,
                )
                settle(claimed, advanced).assert().isTrue()
                // The slot sent is not sent again: a second outcome of its claim does nothing.
                settle(claimed, advanced.copyWith(slot = advanced.slot + 1)).assert().isFalse()
                store.due(partitions, nextDue - 1, LIMIT).assert().isEmpty()
                loaded = store.due(partitions, nextDue, LIMIT).single()
                loaded.assert().isEqualTo(advanced)
                loaded.slot.assert().isEqualTo(index + 2L)
                loaded.requestId.assert().isEqualTo("${timer.id}#${index + 2}")
                loaded.context.due.assert().isEqualTo(nextDue)
            }
            loaded.status.assert().isEqualTo(TimerStatus.PENDING)
        }

    @Test
    fun `a claim long past due coalesces the missed slots, and the skipped count holds through retries`(): Unit =
        runBlocking {
            val timer = timer(key = "invoice", dueAt = FEBRUARY, rule = MONTHLY)
            store.put(timer)
            // Claimed on May 2nd: February to April are skipped, May's slot is sent.
            val coalesced = timer.copyWith(slot = 4, slotDueAt = MAY, skipped = 3).claimed(MAY + DAY + CLAIM)
            settle(timer, coalesced).assert().isTrue()
            store.find(owner, "invoice").assert().isEqualTo(coalesced)
            val retried = coalesced.copyWith(status = TimerStatus.PENDING, attempts = 1, dueAt = MAY + DAY + 5)
            settle(coalesced, retried).assert().isTrue()
            val loaded = store.due(partitions, MAY + DAY + 5, LIMIT).single()
            loaded.skipped.assert().isEqualTo(3L)
            loaded.context.skipped.assert().isEqualTo(3L)
            loaded.context.slot.assert().isEqualTo(4L)
            val reclaimed = loaded.claimed(MAY + DAY + 2 * CLAIM)
            settle(loaded, reclaimed).assert().isTrue()
            val june = reclaimed.copyWith(
                status = TimerStatus.PENDING,
                slot = 5,
                slotDueAt = JUNE,
                dueAt = JUNE,
                skipped = 0,
                attempts = 0,
                outcome = TimerOutcomes.COMMITTED,
            )
            settle(reclaimed, june).assert().isTrue()
            store.find(owner, "invoice").assert().isEqualTo(june)
            // A timer put with skipped slots, as a rebuilt store writes it, reads back so.
            val rebuilt = timer(key = "rebuilt", rule = MONTHLY).copyWith(slot = 7, skipped = 2)
            store.put(rebuilt).assert().isTrue()
            store.find(owner, "rebuilt").assert().isEqualTo(rebuilt)
        }

    @Test
    fun `a timer cancelled between the poll and its claim is not claimed`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer)
        val loaded = store.due(partitions, DUE, LIMIT).single()
        store.cancel(owner, "pay", 1).assert().isTrue()
        settle(loaded, loaded.claimed()).assert().isFalse()
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        // Rescheduled since it was loaded: the claim of the old timer fails too.
        store.put(timer(version = 2))
        settle(loaded, loaded.claimed()).assert().isFalse()
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.PENDING)
    }

    @Test
    fun `a timer is claimed once, and its outcome is written from the claim`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer)
        val claimed = timer.claimed()
        coroutineScope {
            (1..CONTENDERS).map { async(Dispatchers.Default) { store.settle(listOf(TimerChange(timer, claimed))) } }
                .awaitAll()
        }.sum().assert().isEqualTo(1)
        store.find(owner, "pay").assert().isEqualTo(claimed)
        // Claimed, it is not due before its claim's deadline.
        store.due(partitions, DUE + CLAIM - 1, LIMIT).assert().isEmpty()
        settle(claimed, claimed.fired()).assert().isTrue()
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.FIRED)
    }

    @Test
    fun `a claim that lapses is due again and claimed anew`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer)
        val claimed = timer.claimed()
        settle(timer, claimed).assert().isTrue()
        val lapsed = store.due(partitions, DUE + CLAIM, LIMIT).single()
        lapsed.assert().isEqualTo(claimed)
        val reclaimed = lapsed.claimed(DUE + 2 * CLAIM)
        settle(lapsed, reclaimed).assert().isTrue()
        // The first claimer's late outcome no longer applies.
        settle(claimed, claimed.fired()).assert().isFalse()
        store.find(owner, "pay").assert().isEqualTo(reclaimed)
    }

    @Test
    fun `a cancel takes a claimed timer`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer)
        settle(timer, timer.claimed())
        store.cancel(owner, "pay", 1).assert().isTrue()
        settle(timer.claimed(), timer.claimed().fired()).assert().isFalse()
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        store.put(timer(key = "all"))
        settle(timer(key = "all"), timer(key = "all").claimed())
        store.cancelAll(owner, 1).assert().isEqualTo(1)
    }

    @Test
    fun `changes are written together, each on its own condition`(): Unit = runBlocking {
        val first = timer(key = "first")
        val second = timer(key = "second")
        val stale = timer(key = "stale")
        listOf(first, second, stale).forEach { store.put(it) }
        settle(stale, stale.copyWith(attempts = 1, dueAt = DUE + 1))
        val written = store.settle(
            listOf(
                TimerChange(first, first.fired()),
                TimerChange(second, second.fired(TimerOutcomes.REJECTED)),
                TimerChange(stale, stale.fired()),
            ),
        )
        written.assert().isEqualTo(2)
        store.find(owner, "first").assert().isEqualTo(first.fired())
        store.find(owner, "second").assert().isEqualTo(second.fired(TimerOutcomes.REJECTED))
        store.find(owner, "stale")?.status.assert().isEqualTo(TimerStatus.PENDING)
    }

    @Test
    fun `two writers of one loaded timer change it once`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer)
        val written = coroutineScope {
            (1..CONTENDERS).map { attempt ->
                async(Dispatchers.Default) {
                    store.settle(
                        listOf(TimerChange(timer, timer.copyWith(attempts = attempt, outcome = "try $attempt")))
                    )
                }
            }.awaitAll()
        }
        written.sum().assert().isEqualTo(1)
    }

    @Test
    fun `parked timers are found by their target and leave the due ones`(): Unit = runBlocking {
        val first = timer(key = "first", dueAt = DUE + 1)
        val second = timer(key = "second", dueAt = DUE)
        val other = timer(key = "other", target = target.type.ref("tenant", "other-$run"))
        listOf(first, second, other).forEach { store.put(it) }
        val parkedFirst = first.copyWith(status = TimerStatus.PARKED, attempts = 3, outcome = "down")
        val parkedSecond = second.copyWith(status = TimerStatus.PARKED, attempts = 3, outcome = "down")
        settle(first, parkedFirst).assert().isTrue()
        settle(second, parkedSecond).assert().isTrue()
        store.parked(target).assert().containsExactly(parkedSecond, parkedFirst)
        store.due(partitions, DUE + 10, LIMIT).assert().containsExactly(other)
        // Retrying puts one back.
        val retried = parkedSecond.copyWith(status = TimerStatus.PENDING, attempts = 0)
        settle(parkedSecond, retried).assert().isTrue()
        store.parked(target).assert().containsExactly(parkedFirst)
        // A cancel takes a parked timer too.
        store.cancel(owner, "first", 1).assert().isTrue()
        store.parked(target).assert().isEmpty()
    }

    @Test
    fun `concurrent puts of one key keep the latest version`(): Unit = runBlocking {
        coroutineScope {
            (1..CONTENDERS).map { version ->
                async(Dispatchers.Default) { store.put(timer(version = version.toLong(), dueAt = DUE + version)) }
            }.awaitAll()
        }
        store.find(owner, "pay")?.version.assert().isEqualTo(CONTENDERS.toLong())
        store.due(partitions, DUE + CONTENDERS, LIMIT).map { it.id }.assert().containsExactly("$owner#pay@$CONTENDERS")
    }

    private companion object {
        const val LIMIT = 100
        const val CONTENDERS = 8
        const val DUE = 1_700_000_000_000L
        const val HOUR = 3_600_000L
        const val CLAIM = 30_000L
        const val DAY = 86_400_000L
        val MONTHLY = ScheduleRule.Cron("0 0 0 1 * *")

        /** The first of each month of 2026, 00:00 UTC: the slots of [MONTHLY]. */
        const val FEBRUARY = 1_769_904_000_000L
        const val MARCH = 1_772_323_200_000L
        const val APRIL = 1_775_001_600_000L
        const val MAY = 1_777_593_600_000L
        const val JUNE = 1_780_272_000_000L
    }
}
