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
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.observation.Observation

/** An entry's place in a [SubscriptionWorker]'s window: [done] once it completed. */
internal class LaneSlot(val sequence: Long) {
    var done = false
}

/** An item of an aggregate's lane in a [SubscriptionWorker]: a live entry, or an operator's action on the aggregate. */
internal sealed interface LaneItem {
    /**
     * A live entry of a lane, and the [observation] of its handling a run started and did not finish: the lane's
     * coroutine sets it before the lane takes the entry again, which its monitor orders. Its handling goes on in
     * it; a skip ends it as skipped, a stop with its cancellation. [gap] is the version missing before it, when the
     * source did not deliver it ([VersionGaps]): the entry parks its aggregate there, alone, instead of being handled.
     */
    class Live(val entry: SourcedEntry, val slot: LaneSlot, val gap: Int? = null) : LaneItem {
        var observation: Observation? = null
    }

    /** An operator's action on the aggregate, answered with [result]. */
    sealed interface Action : LaneItem {
        val result: CompletableDeferred<Boolean>
    }

    /** A retry of [parked]; [acceptGaps] for an operator's, which goes on past a version the source did not deliver. */
    class Retry(
        val parked: ParkedAggregate,
        override val result: CompletableDeferred<Boolean>,
        val acceptGaps: Boolean,
    ) : Action

    class Skip(override val result: CompletableDeferred<Boolean>) : Action
}
