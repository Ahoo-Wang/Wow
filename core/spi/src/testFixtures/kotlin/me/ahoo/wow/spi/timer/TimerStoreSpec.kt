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

package me.ahoo.wow.spi.timer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The [TimerStore] contract (decision D166) every backend passes. A backend's test extends it and supplies a store
 * from [createStore]. Stores may share storage: each test uses owners, targets and partitions of its own.
 */
abstract class TimerStoreSpec {
    protected abstract fun createStore(): TimerStore

    private val store: TimerStore by lazy { createStore() }

    private val run = UUID.randomUUID().toString()
    private val owner = "shop.order/tenant/o-$run"
    private val target = AggregateType("shop", "order").ref("tenant", "o-$run")

    /** A partition of this test's own, so [TimerStore.due] sees only its timers in a shared store. */
    private val partition = NEXT_PARTITION.getAndAdd(2)

    private fun timer(
        key: String = "pay",
        version: Long = 1,
        dueAt: Long = DUE,
        owner: String = this.owner,
        target: AggregateRef = this.target,
        partition: Int = this.partition,
    ) = Timer(
        id = "$owner#$key@$version",
        owner = owner,
        key = key,
        version = version,
        partition = partition,
        dueAt = dueAt,
        target = target,
        commandType = "shop.order.CancelOrder",
        body = """{"reason":"unpaid","amount":2.50}""",
        causation = Causation("command-1", "$owner@$version"),
        headers = mapOf("app" to "web"),
        operator = Operator("alice", "tenant", crossScope = true),
    )

    @Test
    fun `a timer put is found and due once its time comes`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer).assert().isTrue()
        store.find(owner, "pay").assert().isEqualTo(timer)
        store.find(owner, "other").assert().isNull()
        store.due(partition, DUE - 1, LIMIT).assert().isEmpty()
        store.due(partition, DUE, LIMIT).assert().containsExactly(timer)
    }

    @Test
    fun `a timer without causation, headers or operator is found as put`(): Unit = runBlocking {
        val timer = timer(key = "bare").copy(causation = null, headers = emptyMap(), operator = null)
        store.put(timer).assert().isTrue()
        store.find(owner, "bare").assert().isEqualTo(timer)
    }

    @Test
    fun `due timers come earliest first, within their partition and the limit`(): Unit = runBlocking {
        val late = timer(key = "late", dueAt = DUE + 2)
        val early = timer(key = "early", dueAt = DUE)
        val middle = timer(key = "middle", dueAt = DUE + 1)
        val elsewhere = timer(key = "elsewhere", partition = partition + 1)
        listOf(late, early, middle, elsewhere).forEach { store.put(it) }
        store.due(partition, DUE + 10, LIMIT).assert().containsExactly(early, middle, late)
        store.due(partition, DUE + 10, 2).assert().containsExactly(early, middle)
        store.due(partition, DUE + 1, LIMIT).assert().containsExactly(early, middle)
    }

    @Test
    fun `putting the same timer again changes nothing, and never revives it`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer).assert().isTrue()
        store.put(timer).assert().isFalse()
        val fired = timer.copy(status = TimerStatus.FIRED, attempts = 1, outcome = TimerOutcomes.COMMITTED)
        store.update(fired, TimerStatus.PENDING).assert().isTrue()
        store.put(timer).assert().isFalse()
        store.find(owner, "pay").assert().isEqualTo(fired)
        store.due(partition, DUE, LIMIT).assert().isEmpty()
    }

    @Test
    fun `a later version reschedules, an earlier one is ignored`(): Unit = runBlocking {
        val first = timer(version = 1)
        val second = timer(version = 3, dueAt = DUE + 100)
        store.put(first)
        store.update(first.copy(status = TimerStatus.FIRED, outcome = TimerOutcomes.REJECTED), TimerStatus.PENDING)
        store.put(second).assert().isTrue()
        store.find(owner, "pay").assert().isEqualTo(second)
        store.put(timer(version = 2)).assert().isFalse()
        store.put(first).assert().isFalse()
        store.find(owner, "pay").assert().isEqualTo(second)
        store.due(partition, DUE + 100, LIMIT).assert().containsExactly(second)
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
        store.due(partition, DUE, LIMIT).assert().isEmpty()
        // A replay of the cancelled timer's put does not revive it.
        store.put(timer(version = 2)).assert().isFalse()
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
    }

    @Test
    fun `a cancel leaves a fired timer as it is`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer)
        val fired = timer.copy(status = TimerStatus.FIRED, attempts = 1, outcome = TimerOutcomes.COMMITTED)
        store.update(fired, TimerStatus.PENDING)
        store.cancel(owner, "pay", 2).assert().isFalse()
        store.find(owner, "pay").assert().isEqualTo(fired)
    }

    @Test
    fun `cancelling all of an owner's timers takes the pending ones only`(): Unit = runBlocking {
        val pay = timer(key = "pay")
        val remind = timer(key = "remind", version = 2)
        val fired = timer(key = "fired")
        val later = timer(key = "later", version = 9)
        val others = timer(owner = "$owner-other")
        listOf(pay, remind, fired, later, others).forEach { store.put(it) }
        store.update(fired.copy(status = TimerStatus.FIRED), TimerStatus.PENDING)
        store.cancelAll(owner, 5).assert().isEqualTo(2)
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        store.find(owner, "remind")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        store.find(owner, "fired")?.status.assert().isEqualTo(TimerStatus.FIRED)
        store.find(owner, "later")?.status.assert().isEqualTo(TimerStatus.PENDING)
        store.find("$owner-other", "pay")?.status.assert().isEqualTo(TimerStatus.PENDING)
    }

    @Test
    fun `an update applies only to the same timer in the status read`(): Unit = runBlocking {
        val timer = timer()
        store.put(timer)
        val retried = timer.copy(attempts = 1, dueAt = DUE + 5, outcome = "java.io.IOException: down")
        store.update(retried, TimerStatus.FIRED).assert().isFalse()
        store.update(retried, TimerStatus.PENDING).assert().isTrue()
        store.find(owner, "pay").assert().isEqualTo(retried)
        store.due(partition, DUE, LIMIT).assert().isEmpty()
        store.due(partition, DUE + 5, LIMIT).assert().containsExactly(retried)
        val rescheduled = timer(version = 2)
        store.put(rescheduled)
        store.update(retried.copy(status = TimerStatus.FIRED), TimerStatus.PENDING).assert().isFalse()
        store.find(owner, "pay").assert().isEqualTo(rescheduled)
        store.update(timer(key = "missing").copy(status = TimerStatus.FIRED), TimerStatus.PENDING).assert().isFalse()
    }

    @Test
    fun `parked timers are found by their target and leave the due ones`(): Unit = runBlocking {
        val first = timer(key = "first", dueAt = DUE + 1)
        val second = timer(key = "second", dueAt = DUE)
        val other = timer(key = "other", target = target.type.ref("tenant", "other-$run"))
        listOf(first, second, other).forEach { store.put(it) }
        val parkedFirst = first.copy(status = TimerStatus.PARKED, attempts = 3, outcome = "down")
        val parkedSecond = second.copy(status = TimerStatus.PARKED, attempts = 3, outcome = "down")
        store.update(parkedFirst, TimerStatus.PENDING).assert().isTrue()
        store.update(parkedSecond, TimerStatus.PENDING).assert().isTrue()
        store.parked(target).assert().containsExactly(parkedSecond, parkedFirst)
        store.due(partition, DUE + 10, LIMIT).assert().containsExactly(other)
        // Resuming puts one back.
        store.update(parkedSecond.copy(status = TimerStatus.PENDING, attempts = 0), TimerStatus.PARKED).assert().isTrue()
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
        store.due(partition, DUE + CONTENDERS, LIMIT).map { it.id }.assert().containsExactly("$owner#pay@$CONTENDERS")
    }

    private companion object {
        /** Two partitions per test, from a random start, so runs sharing a database do not meet either. */
        val NEXT_PARTITION = AtomicInteger(Random.nextInt(0, Int.MAX_VALUE / 2))
        const val LIMIT = 100
        const val CONTENDERS = 8
        const val DUE = 1_700_000_000_000L
    }
}
