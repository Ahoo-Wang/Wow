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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.HandlingRejectedException
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The waits of a worker's entries whose next hop is its subscription: which entries carry one, and what the signal says
 * once the entry is done with, best effort.
 */
class WaitHopsTest {
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)
    private val sent = CopyOnWriteArrayList<WaitSignal>()

    @Volatile
    private var failure: Throwable? = null
    private val signals = SignalSender { _, signal, _ ->
        failure?.let { throw it }
        sent += signal
    }
    private val subscription = Subscription(NAME, SubscriptionFilter("shop")) { }
    private val hops = WaitHops(subscription, signals, clock)
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")

    private fun entry(vararg remaining: Hop, deadline: Long = NOW + 1_000, waits: Boolean = true) = SourcedEntry(
        LogPosition(0, 1),
        EventStreamRecord(
            cart("x"),
            1,
            "c-1",
            "r-1",
            "shop.cart.Act",
            0L,
            listOf(EventRecord("shop.cart.Added", 1, "{}")),
            Scope.NONE
        ),
        if (waits) Transport(WaitContext("w-1", origin, remaining.toList(), deadline)) else null,
    )

    @Test
    fun `an entry waits here when its next hop is this subscription's handling, in its context or in none`() {
        hops.waitsHere(entry(Hop.handled(NAME))).assert().isTrue()
        hops.waitsHere(entry(Hop.handled(NAME, "shop"))).assert().isTrue()
        hops.waitsHere(entry(Hop.handled(NAME, "stock"))).assert().isFalse()
        hops.waitsHere(entry(Hop.handled("Other.onCart"))).assert().isFalse()
        hops.waitsHere(entry(Hop.committed(), Hop.handled(NAME))).assert().isFalse()
        hops.waitsHere(entry(waits = false)).assert().isFalse()
    }

    @Test
    fun `a handled entry signals SUCCEEDED with what it derived, a rejection REJECTED`(): Unit = runBlocking {
        hops.signal(entry(Hop.handled(NAME)), Result.success(HandledEntry(listOf("c-2"))))
        sent.single().result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        sent.single().waitId.assert().isEqualTo("w-1")
        sent.clear()
        hops.signal(entry(Hop.handled(NAME)), Result.success(HandledEntry(rejection = ErrorInfo("shop.no", "No."))))
        sent.single().result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        sent.single().result.error!!.code.assert().isEqualTo("shop.no")
        sent.clear()
        val rejected = HandlingRejectedException(ErrorInfo("shop.refused", "Refused."), "refused")
        hops.signal(entry(Hop.handled(NAME)), Result.failure(rejected))
        sent.single().result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        sent.single().result.error!!.code.assert().isEqualTo("shop.refused")
    }

    @Test
    fun `a failure or a parking signals FAILED with where it parked, never the handler's exception`(): Unit =
        runBlocking {
            hops.signal(entry(Hop.handled(NAME)), Result.failure(IllegalStateException("jdbc://10.0.3.5 refused")))
            sent.single().result.status.assert().isEqualTo(CommandResult.Status.FAILED)
            sent.single().result.error!!.code.assert().isEqualTo(ResultCodes.SUBSCRIPTION_PARKED)
            sent.single().result.error!!.message.assert().doesNotContain("10.0.3.5")
            sent.clear()
            hops.signal(entry(Hop.handled(NAME)), Result.success(HandledEntry.NOTHING), parked = true)
            sent.single().result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        }

    @Test
    fun `nothing is signalled to a wait that is over or does not wait here`(): Unit = runBlocking {
        hops.signal(entry(Hop.handled(NAME), deadline = NOW - 1), Result.success(HandledEntry.NOTHING))
        hops.signal(entry(Hop.handled("Other.onCart")), Result.success(HandledEntry.NOTHING))
        sent.assert().isEmpty()
    }

    @Test
    fun `a signal that cannot be sent is the wait's loss, never the entry's, unless the lane is cancelled`(): Unit =
        runBlocking {
            failure = IllegalStateException("The origin is gone.")
            hops.signal(entry(Hop.handled(NAME)), Result.success(HandledEntry.NOTHING))
            failure = CancellationException("The lane stopped.")
            assertThrows<CancellationException> {
                runBlocking { hops.signal(entry(Hop.handled(NAME)), Result.success(HandledEntry.NOTHING)) }
            }
            sent.assert().isEmpty()
        }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val NAME = "Board.onCart"
    }
}
