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

package me.ahoo.wow.runtime.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.kernel.SnapshotLoadSkippedException
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore

/**
 * The snapshot loads of one placed store, gated by the record of its [activity]: while its loads are skipped
 * ([StoreActivity.loadsSkipped]), a load does not call the store and throws [SnapshotLoadSkippedException], which the
 * kernel's cache-miss load takes as no snapshot and replays the log at once, counted as skipped. Otherwise it calls
 * the store and records the outcome: an answer, or a failure, a timeout of the kernel's load bound among them (the
 * [TimeoutCancellationException] its `withTimeoutOrNull` raises inside the call; a caller's own cancellation is not a
 * failure of the store). The cost on a store that answers is two volatile reads, no write.
 *
 * Only the cache-miss loads, which already take a failed load as none, go through it (the kernel's, the shared
 * states'); the state reads and the snapshot subscriptions call the store as they did.
 */
internal class SkippingSnapshots(private val store: SnapshotStore, private val activity: StoreActivity) :
    SnapshotStore by store {
    override suspend fun load(ref: AggregateRef): SnapshotRecord? {
        if (activity.loadsSkipped) {
            throw SnapshotLoadSkippedException("the store ${activity.name} does not answer lately")
        }
        val loaded = try {
            store.load(ref)
        } catch (timedOut: TimeoutCancellationException) {
            activity.loadFailed()
            throw timedOut
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") // Recorded, then rethrown unchanged.
            failure: Exception,
        ) {
            activity.loadFailed()
            throw failure
        }
        activity.loadAnswered()
        return loaded
    }

    override fun toString(): String = "SkippingSnapshots($store)"
}
