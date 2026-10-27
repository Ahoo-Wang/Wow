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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The three classes of a handler's failure (decisions D229 G-6, D243): an unrecoverable one parks the aggregate at
 * once, a recoverable one is retried within its budget and then parks it, a systemic one pauses the partition until
 * a handling succeeds, charging and parking no aggregate and writing no ledger entry meanwhile.
 */
class SubscriptionPauseTest {
    private val log = InMemoryEventLog(partitions = 1)
    private val appender = Appender(log)
    private val ledger = RecordingLedger(InMemoryFailureLedger())
    private val checkpoints = RecordingCheckpoints()
    private val pauses = RecordingPauses()
    private val options = testOptions(maxAttempts = MAX_ATTEMPTS).copyWith(
        systemic = SystemicFailureOptions(initialBackoff = 2.milliseconds, maxBackoff = 10.milliseconds),
    )

    private fun runtime(
        subscription: Subscription,
        classification: FailureClassification = FailureClassification.DEFAULT,
        options: SubscriptionOptions = this.options,
    ) = SubscriptionRuntime(
        "group",
        log,
        checkpoints,
        ledger,
        listOf(subscription),
        options,
        Clock.systemUTC(),
        observer = pauses,
        classification = classification,
    ).also { ledger.runtime.set(it) }

    @Test
    fun `a downstream outage of a few seconds pauses the partition and resumes it without parking`(): Unit =
        runBlocking {
            val down = AtomicBoolean(true)
            val attempts = AtomicInteger()
            val handled = Handled()
            val projection = Subscription("projection", SubscriptionFilter("shop")) { entry ->
                attempts.incrementAndGet()
                if (down.get()) {
                    throw StorageUnavailableException("The view store is unavailable; retry later.")
                }
                handled.record(entry)
            }
            // The default budget, spent in about 1.5 s; probes at most every 500 ms.
            val defaults = options.copyWith(
                retry = RetryPolicy(),
                systemic = SystemicFailureOptions(initialBackoff = 250.milliseconds, maxBackoff = 500.milliseconds),
            )
            val runtime = runtime(projection, options = defaults)
            listOf("x", "y", "z").forEach { appender.append(it) }
            runtime.start(setOf(0))
            eventually { runtime.paused().isNotEmpty() }
            runtime.paused().single().assert().satisfies({
                it.group.assert().isEqualTo("group")
                it.subscription.assert().isEqualTo("projection")
                it.partition.assert().isEqualTo(0)
                it.reason.assert().contains(StorageUnavailableException::class.java.name)
            })
            delay(OUTAGE)
            down.set(false)
            eventually { handled.size == 3 }
            eventually { checkpoints.load("group", "projection", 0) == appender.ends[0] }
            runtime.stop()

            // More attempts than all three budgets together, and none parked.
            attempts.get().assert().isGreaterThan(RetryPolicy().maxAttempts + 3)
            ledger.parks.assert().isEmpty()
            runtime.paused().assert().isEmpty()
            pauses.events.assert().containsExactly("paused projection@0", "resumed projection@0")
        }

    @Test
    fun `many aggregates failing alike within the window pause the partition`(): Unit = runBlocking {
        val down = AtomicBoolean(true)
        val attempts = AtomicInteger()
        val handled = Handled()
        val reactor = Subscription("reactor", SubscriptionFilter("shop")) { entry ->
            attempts.incrementAndGet()
            check(!down.get()) { "The carrier answers 503." }
            handled.record(entry)
        }
        // Slow enough that every lane fails once before any spends its budget.
        val slowRetries = options.copyWith(
            retry = RetryPolicy(maxAttempts = MAX_ATTEMPTS, initialBackoff = 200.milliseconds, maxBackoff = 1.seconds)
        )
        val runtime = runtime(reactor, options = slowRetries)
        listOf("x", "y", "z").forEach { appender.append(it) }
        runtime.start(setOf(0))
        eventually { runtime.paused().isNotEmpty() }
        eventually { attempts.get() > 4 * MAX_ATTEMPTS * 3 }
        runtime.paused().single().reason.assert().contains("3 aggregates failed within 10s")
        down.set(false)
        eventually { handled.size == 3 }
        runtime.stop()

        ledger.parks.assert().isEmpty()
        pauses.events.assert().containsExactly("paused reactor@0", "resumed reactor@0")
    }

    @Test
    fun `an aggregate's own unrecoverable failure parks only it, without a retry`(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val handled = Handled()
        val projection = Subscription(
            "projection",
            SubscriptionFilter("shop"),
            retry = HandlerRetry(unrecoverable = listOf(IllegalArgumentException::class.java)),
        ) { entry ->
            if (entry.ref.id == "x") {
                attempts.incrementAndGet()
                throw IllegalArgumentException("x holds an invalid address")
            }
            handled.record(entry)
        }
        val runtime = runtime(projection)
        listOf("x", "y", "x", "y").forEach { appender.append(it) }
        runtime.start(setOf(0))
        eventually { ledger.isParked("projection", cart("x")) && handled.size == 2 }
        eventually { checkpoints.load("group", "projection", 0) == appender.ends[0] }
        runtime.stop()

        attempts.get().assert().isEqualTo(1)
        handled.versionsOf("y").assert().containsExactly(1, 2)
        ledger.parks.map { it.ref.id }.assert().containsExactly("x")
        // Not retried automatically either (9.x RecoverableType.UNRECOVERABLE).
        ledger.parked("projection", cart("x"))!!.recoverable.assert().isEqualTo(Recoverable.UNRECOVERABLE)
        pauses.events.assert().isEmpty()
    }

    @Test
    fun `a pause spends nothing of the budget of the aggregate that failed in it`(): Unit = runBlocking {
        val yHandled = AtomicBoolean(false)
        val systemic = AtomicInteger()
        val own = AtomicInteger()
        val projection = Subscription("projection", SubscriptionFilter("shop")) { entry ->
            if (entry.ref.id == "y") {
                yHandled.set(true)
                return@Subscription
            }
            if (!yHandled.get()) {
                systemic.incrementAndGet()
                throw StorageUnavailableException("The view store is unavailable; retry later.")
            }
            own.incrementAndGet()
            error("x is broken on its own")
        }
        val runtime = runtime(projection)
        appender.append("x")
        runtime.start(setOf(0))
        eventually { runtime.paused().isNotEmpty() && systemic.get() > 2 * MAX_ATTEMPTS }
        // y's handling succeeds and resumes the partition; x then fails on its own.
        appender.append("y")
        eventually { ledger.isParked("projection", cart("x")) }
        runtime.stop()

        own.get().assert().isEqualTo(MAX_ATTEMPTS)
        ledger.parked("projection", cart("x"))!!.assert().satisfies({
            it.reason.assert().contains("x is broken on its own")
            it.recoverable.assert().isEqualTo(Recoverable.UNKNOWN)
        })
        pauses.events.assert().containsExactly("paused projection@0", "resumed projection@0")
    }

    @Test
    fun `nothing is parked while the partition is paused, an unrecoverable failure included`(): Unit = runBlocking {
        val down = AtomicBoolean(true)
        val unrecoverable = AtomicInteger()
        val projection = Subscription(
            "projection",
            SubscriptionFilter("shop"),
            retry = HandlerRetry(unrecoverable = listOf(IllegalArgumentException::class.java)),
        ) { entry ->
            if (entry.ref.id == "u") {
                unrecoverable.incrementAndGet()
                throw IllegalArgumentException("u holds an invalid address")
            }
            if (down.get()) {
                throw StorageUnavailableException("The view store is unavailable; retry later.")
            }
        }
        val runtime = runtime(projection)
        appender.append("x")
        runtime.start(setOf(0))
        eventually { runtime.paused().isNotEmpty() }
        appender.append("u")
        eventually { unrecoverable.get() >= 2 }
        ledger.parks.assert().isEmpty()
        down.set(false)
        eventually { ledger.isParked("projection", cart("u")) }
        runtime.stop()

        ledger.parks.map { it.ref.id }.assert().containsExactly("u")
        ledger.parkedWhilePaused.get().assert().isZero()
        ledger.isParked("projection", cart("x")).assert().isFalse()
    }

    @Test
    fun `a handler that takes a systemic failure for recoverable retries and parks its aggregate`(): Unit =
        runBlocking {
            val attempts = AtomicInteger()
            val projection = Subscription(
                "projection",
                SubscriptionFilter("shop"),
                retry = HandlerRetry(recoverable = listOf(StorageUnavailableException::class.java)),
            ) {
                attempts.incrementAndGet()
                throw StorageUnavailableException("The view store is unavailable; retry later.")
            }
            val runtime = runtime(projection)
            appender.append("x")
            runtime.start(setOf(0))
            eventually { ledger.isParked("projection", cart("x")) }
            runtime.stop()

            attempts.get().assert().isEqualTo(MAX_ATTEMPTS)
            pauses.events.assert().isEmpty()
        }

    @Test
    fun `an application's classifier is asked for the failure and its causes`(): Unit = runBlocking {
        val down = AtomicBoolean(true)
        val handled = Handled()
        val reactor = Subscription("reactor", SubscriptionFilter("shop")) { entry ->
            if (down.get()) {
                throw IllegalStateException("The carrier call failed.", CarrierDownException())
            }
            handled.record(entry)
        }
        val classifier = FailureClassifier { if (it is CarrierDownException) FailureClass.SYSTEMIC else null }
        val runtime = runtime(reactor, FailureClassification.of(listOf(classifier)))
        appender.append("x")
        runtime.start(setOf(0))
        eventually { runtime.paused().isNotEmpty() }
        runtime.paused().single().reason.assert().contains("The carrier call failed.")
        down.set(false)
        eventually { handled.size == 1 }
        runtime.stop()

        ledger.parks.assert().isEmpty()
        pauses.events.assert().containsExactly("paused reactor@0", "resumed reactor@0")
    }

    @Test
    fun `the parked retries of a paused partition wait for it to run again`(): Unit = runBlocking {
        val down = AtomicBoolean(true)
        val handled = Handled()
        val projection = Subscription("projection", SubscriptionFilter("shop")) { entry ->
            if (down.get() && entry.ref.id == "x") {
                throw StorageUnavailableException("The view store is unavailable; retry later.")
            }
            handled.record(entry)
        }
        val scanning = options.copyWith(
            parked = ParkedRetryOptions(
                spec = ParkedRetrySpec(initialBackoff = 1.milliseconds),
                scanInterval = 5.milliseconds
            ),
        )
        val runtime = runtime(projection, options = scanning)
        appender.append("p")
        ledger.park(ParkedAggregate("projection", cart("p"), 1, "IllegalStateException: p was broken", 0L))
        ledger.parks.clear()
        appender.append("x")
        runtime.start(setOf(0))
        eventually { runtime.paused().isNotEmpty() }
        delay((PAUSED_SCANS * 5).milliseconds)
        ledger.claims.get().assert().isZero()
        down.set(false)
        eventually { !ledger.isParked("projection", cart("p")) }
        runtime.stop()

        ledger.claims.get().assert().isPositive()
        handled.versionsOf("p").assert().containsExactly(1)
        handled.versionsOf("x").assert().containsExactly(1)
    }

    private class CarrierDownException : RuntimeException("The carrier is unreachable.")

    /** Hears the pauses and resumes. */
    private class RecordingPauses : Observer {
        val events = CopyOnWriteArrayList<String>()

        override fun partitionPaused(group: String, subscription: String, partition: Int, reason: String) {
            events += "paused $subscription@$partition"
        }

        override fun partitionResumed(group: String, subscription: String, partition: Int, pausedMillis: Long) {
            events += "resumed $subscription@$partition"
        }
    }

    /** Records each entry parked, and whether the partition was paused then; counts the claimed retries. */
    private class RecordingLedger(private val delegate: FailureLedger) : FailureLedger by delegate {
        val runtime = AtomicReference<SubscriptionRuntime>()
        val parks = CopyOnWriteArrayList<ParkedAggregate>()
        val parkedWhilePaused = AtomicInteger()
        val claims = AtomicInteger()

        override suspend fun park(entry: ParkedAggregate): Boolean {
            if (runtime.get()?.paused()?.isNotEmpty() == true) {
                parkedWhilePaused.incrementAndGet()
            }
            parks += entry
            return delegate.park(entry)
        }

        override suspend fun amend(
            subscription: String,
            ref: AggregateRef,
            amendment: ParkedAmendment,
        ): ParkedAggregate? {
            if (amendment is ParkedAmendment.ClaimRetry) {
                claims.incrementAndGet()
            }
            return delegate.amend(subscription, ref, amendment)
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val PAUSED_SCANS = 10
        val OUTAGE = 3.seconds
    }
}
