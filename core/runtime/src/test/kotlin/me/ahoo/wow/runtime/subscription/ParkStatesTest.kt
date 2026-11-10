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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The parking of a worker's aggregates: what it holds back, and the retry or skip running on each. */
class ParkStatesTest {
    private val x = cart("x")
    private val y = cart("y")

    @Test
    fun `a parked aggregate is held from its parked ordinal on, an earlier parking keeping the earlier one`() {
        val parking = ParkStates(mapOf(x to 3L))
        parking.holds(x, 2).assert().isFalse()
        parking.holds(x, 3).assert().isTrue()
        parking.holds(y, 1).assert().isFalse()
        parking.park(x, 5)
        parking.holds(x, 3).assert().isTrue()
        parking.park(x, 2)
        parking.holds(x, 2).assert().isTrue()
        parking.park(y, 4)
        parking.holds(y, 4).assert().isTrue()
        parking.holds(y, 3).assert().isFalse()
    }

    @Test
    fun `a retry holds nothing back, is answered by the one running, and a failed one parks again`(): Unit =
        runBlocking {
            val parking = ParkStates(mapOf(x to 3L))
            parking.running(x).assert().isNull()
            val result = CompletableDeferred<Boolean>()
            parking.retrying(x, result)
            parking.running(x).assert().isSameAs(result)
            // New entries queue behind the retry instead of being skipped; a park meanwhile is the retry's to settle.
            parking.holds(x, 9).assert().isFalse()
            parking.park(x, 1)
            parking.holds(x, 1).assert().isFalse()
            // Another retry's result does not restore it.
            parking.parkedAgain(x, CompletableDeferred(), 4)
            parking.holds(x, 9).assert().isFalse()
            parking.parkedAgain(x, result, 4)
            parking.holds(x, 4).assert().isTrue()
            parking.holds(x, 3).assert().isFalse()
        }

    @Test
    fun `a resolved retry skips later copies of what it handled, until a newer entry arrives in the lane`() {
        val parking = ParkStates(mapOf(x to 3L))
        parking.retrying(x, CompletableDeferred())
        parking.resolved(x)
        parking.retried(x, null)
        parking.retriedCopy(x, 1).assert().isFalse()
        parking.retried(x, 5)
        parking.retriedCopy(x, 5).assert().isTrue()
        parking.retriedCopy(x, 6).assert().isFalse()
        parking.retriedCopyInLane(x, 4).assert().isTrue()
        parking.retriedCopyInLane(x, 6).assert().isFalse()
        // Every later entry is newer than what the retry read: no copy any more.
        parking.retriedCopy(x, 5).assert().isFalse()
        parking.retriedCopyInLane(y, 1).assert().isFalse()
    }

    @Test
    fun `a retry's copies are not skipped on admission while the aggregate is parked again`() {
        val parking = ParkStates(emptyMap())
        parking.retried(x, 5)
        parking.park(x, 7)
        parking.retriedCopy(x, 4).assert().isFalse()
        parking.holds(x, 7).assert().isTrue()
    }

    @Test
    fun `a retry and a skip of one aggregate exclude each other`(): Unit = runBlocking {
        val parking = ParkStates(mapOf(x to 3L))
        parking.skipping(x).assert().isTrue()
        // The parked entries stay skipped while it runs; a retry meanwhile answers false, a second skip is refused.
        parking.holds(x, 3).assert().isTrue()
        parking.running(x)!!.await().assert().isFalse()
        parking.skipping(x).assert().isFalse()
        parking.park(x, 1)
        parking.holds(x, 1).assert().isFalse()
        parking.skipped(x, resolved = true)
        parking.holds(x, 3).assert().isFalse()
        parking.running(x).assert().isNull()

        parking.retrying(y, CompletableDeferred())
        parking.skipping(y).assert().isFalse()
    }

    @Test
    fun `a skip whose ledger write failed leaves the aggregate as it found it`() {
        val parking = ParkStates(mapOf(x to 3L))
        parking.skipping(x).assert().isTrue()
        parking.skipped(x, resolved = false)
        parking.holds(x, 3).assert().isTrue()
        // An aggregate the worker did not hold, skipped by its ledger entry alone: nothing to restore.
        parking.skipping(y).assert().isTrue()
        parking.holds(y, 1).assert().isFalse()
        parking.skipped(y, resolved = false)
        parking.holds(y, 1).assert().isFalse()
        parking.running(y).assert().isNull()
        // Ending a skip that does not run changes nothing.
        parking.skipped(x, resolved = true)
        parking.holds(x, 3).assert().isTrue()
    }
}
