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

package me.ahoo.wow.runtime.ledger

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.StorageRefusedException
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.observation.ObservedEvent.MaintenanceSkipped.Work
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The skips of a context's background work on their own: what the walk's and the rebuild's tests do not reach. */
class MaintenanceSkipsTest {
    private val type = AggregateType("office", "document")
    private val other = AggregateType("office", "other")
    private val ledger = InMemoryFailureLedger()
    private val skips =
        MaintenanceSkips("office", Work.ACCESS_WALK, { of -> SkipLedger(ledger, 4).takeIf { of == type } })
    private val parked = SubscriptionId("office", MaintenanceSkips.ACCESS_WALK)

    @Test
    fun `a type without a ledger skips nothing, a failure of it stopping the work`(): Unit = runBlocking {
        val ref = other.ref("tenant", "a")

        assertThrows<StorageRefusedException> {
            runBlocking { skips.attempt(ref) { throw StorageRefusedException("refused") } }
        }
        skips.attempt(ref) {}.assert().isTrue()
        skips.retry(ref) { true }.assert().isTrue()
        skips.absent(ref).assert().isFalse()
        skips.skip(ref).assert().isTrue()
        skips.toString().assert().isEqualTo("MaintenanceSkips(office, access-walk)")
    }

    @Test
    fun `an operator's retry resolves on success, keeps the entry when it fails again, and rethrows the rest`(): Unit =
        runBlocking {
            val ref = type.ref("tenant", "a")
            ledger.park(ParkedAggregate(parked, ref, 0, 1, "skipped", 1))

            skips.retry(ref) { throw UnreplayableHistoryException(ref, IllegalStateException("again")) }
                .assert().isFalse()
            ledger.isParked(parked, ref).assert().isTrue()
            assertThrows<StorageUnavailableException> {
                runBlocking { skips.retry(ref) { throw StorageUnavailableException("down") } }
            }
            assertThrows<CancellationException> {
                runBlocking { skips.retry(ref) { throw CancellationException("stopping") } }
            }
            skips.retry(ref) { false }.assert().isFalse()
            ledger.isParked(parked, ref).assert().isTrue()

            skips.retry(ref) { true }.assert().isTrue()
            ledger.isParked(parked, ref).assert().isFalse()
        }

    @Test
    fun `beyond the entries it lists, every success resolves its aggregate's entry by key`(): Unit = runBlocking {
        repeat(MANY) {
            val ref = type.ref("tenant", "p-%06d".format(it))
            ledger.park(ParkedAggregate(parked, ref, 0, 1, "skipped", 1))
        }
        val last = type.ref("tenant", "p-%06d".format(MANY - 1))

        skips.attempt(last) {}.assert().isTrue()

        ledger.isParked(parked, last).assert().isFalse()
    }

    private companion object {
        /** More than the entries a run lists, a page past them. */
        const val MANY = 10_200
    }
}
