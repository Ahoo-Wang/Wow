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

package me.ahoo.wow.spi.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import org.junit.jupiter.api.Test

class SnapshotStoreWritesInFlightTest {
    private open class Minimal : SnapshotStore {
        override suspend fun save(snapshot: SnapshotRecord): Boolean = true

        override suspend fun load(ref: AggregateRef): SnapshotRecord? = null
    }

    @Test
    fun `a store that says nothing should catch up with its writes in flight, the same count whatever the load`() {
        val store = Minimal()
        store.writesInFlight.assert().isEqualTo(SnapshotStore.DEFAULT_WRITES_IN_FLIGHT)
        store.catchUpWritesInFlight.assert().isEqualTo(store.writesInFlight)
        val raised = object : Minimal() {
            override val writesInFlight: Int get() = 4
        }
        raised.catchUpWritesInFlight.assert().isEqualTo(4)
    }
}
