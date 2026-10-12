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

package me.ahoo.wow.spi.ledger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The [FailureLedger] contract (documentation/designs/v10/04-subscriptions.md §2) every backend passes. A backend's
 * test extends it and supplies a ledger from [createLedger].
 */
abstract class FailureLedgerSpec {
    /** A ledger. Ledgers may share storage: each test uses subscriptions of its own. */
    protected abstract fun createLedger(): FailureLedger

    private val subscription = "sub-" + UUID.randomUUID()
    private val another = "$subscription-another"
    private val type = AggregateType("ledger", "order")

    private val ledger: FailureLedger by lazy { createLedger() }

    private fun ref(id: String, tenant: String = "tenant") = type.ref(tenant, id)

    private fun entry(
        ref: AggregateRef,
        fromVersion: Int,
        reason: String = "failed at $fromVersion",
        subscription: String = this.subscription,
    ) = ParkedAggregate(subscription, ref, fromVersion, reason, FAILED_AT + fromVersion)

    @Test
    fun `a parked aggregate reads back for its subscription only`(): Unit = runBlocking {
        val parked = entry(ref("a"), 3, reason = "boom: ü \" é")
        ledger.park(parked).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(parked)
        ledger.isParked(subscription, ref("a")).assert().isTrue()
        ledger.isParked(subscription, ref("b")).assert().isFalse()
        ledger.isParked(another, ref("a")).assert().isFalse()
        ledger.isParked(subscription, ref("a", tenant = "other")).assert().isFalse()
        ledger.isParked(subscription, AggregateType("other", "order").ref("tenant", "a")).assert().isFalse()
    }

    @Test
    fun `parking an aggregate again keeps the earliest from version`(): Unit = runBlocking {
        val first = entry(ref("a"), 5)
        ledger.park(first).assert().isTrue()
        ledger.park(first).assert().isFalse()
        ledger.park(entry(ref("a"), 7)).assert().isFalse()
        ledger.park(entry(ref("a"), 5, reason = "again")).assert().isFalse()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(first)
        val earlier = entry(ref("a"), 2)
        ledger.park(earlier).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(earlier)
    }

    @Test
    fun `concurrent parks of one aggregate keep the earliest from version`(): Unit = runBlocking {
        val changed = coroutineScope {
            (CONCURRENT_PARKS downTo 1).map { version ->
                async(Dispatchers.Default) { ledger.park(entry(ref("a"), version)) }
            }.awaitAll()
        }
        changed.assert().contains(true)
        ledger.parked(subscription, ref("a")).assert().isEqualTo(entry(ref("a"), 1))
    }

    @Test
    fun `a subscription's parked aggregates are listed in pages, in aggregate order`(): Unit = runBlocking {
        val refs = listOf(ref("b"), ref("a", tenant = "u2"), ref("a"), ref("c"), ref("a", tenant = "u1"))
        refs.forEach { ledger.park(entry(it, 1)) }
        ledger.park(entry(ref("z"), 1, subscription = another))
        val expected = listOf(ref("a"), ref("b"), ref("c"), ref("a", tenant = "u1"), ref("a", tenant = "u2"))
        val first = ledger.list(subscription, limit = 2)
        first.map { it.ref }.assert().isEqualTo(expected.take(2))
        val second = ledger.list(subscription, after = first.last().ref, limit = 2)
        second.map { it.ref }.assert().isEqualTo(expected.subList(2, 4))
        val third = ledger.list(subscription, after = second.last().ref, limit = 2)
        third.map { it.ref }.assert().isEqualTo(expected.drop(4))
        ledger.list(subscription, after = third.last().ref, limit = 2).assert().isEmpty()
        ledger.list(subscription).map { it.ref }.assert().isEqualTo(expected)
        ledger.list("$subscription-none").assert().isEmpty()
    }

    @Test
    fun `resolving removes the entry, after which the aggregate may be parked anew`(): Unit = runBlocking {
        val parked = entry(ref("a"), 4)
        ledger.park(parked)
        ledger.park(entry(ref("a"), 4, subscription = another))
        ledger.resolve(subscription, ref("a")).assert().isEqualTo(parked)
        ledger.isParked(subscription, ref("a")).assert().isFalse()
        ledger.resolve(subscription, ref("a")).assert().isNull()
        ledger.isParked(another, ref("a")).assert().isTrue()
        val later = entry(ref("a"), 9)
        ledger.park(later).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(later)
    }

    protected companion object {
        const val FAILED_AT = 1_700_000_000_000L
        const val CONCURRENT_PARKS = 12
    }
}
