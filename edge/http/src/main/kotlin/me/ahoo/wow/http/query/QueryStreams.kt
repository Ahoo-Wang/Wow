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

package me.ahoo.wow.http.query

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.QueryGateway
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How the public port streams a `list` as server-sent events: keyset chunks of [chunkSize]
 * records, each record's event with the cursor token of its position as its `id`, resumed after the position a request's
 * `Last-Event-ID` names. A stream is bounded by time and bulkhead permits, not rows: after [maxHold]
 * ([Duration.ZERO]: no bound) it ends with an `end` event `{"reason":"hold-expired","resumable":true}`, and the client
 * resumes it. [close] ends every open stream with `node-stopping` and refuses new ones (`wow.node.closing`), so a node
 * that stops does not wait for its streams.
 */
class QueryStreams @InternalWowApi constructor(
    val chunkSize: Int = QueryGateway.DEFAULT_STREAM_CHUNK_SIZE,
    val maxHold: Duration = DEFAULT_MAX_HOLD,
) {
    init {
        require(chunkSize >= 1) { "A stream reads at least one record per chunk, not $chunkSize." }
        require(!maxHold.isNegative()) { "A stream's hold time is never negative, not $maxHold." }
    }

    private val closing = CompletableDeferred<Unit>()

    /** Whether [close] was called. */
    val closed: Boolean
        get() = closing.isCompleted

    /** Completes when [close] is called. */
    internal val stopping: Deferred<Unit>
        get() = closing

    /** Ends every open stream with `node-stopping`, and refuses the streams asked for from now on. */
    @InternalWowApi
    fun close() {
        closing.complete(Unit)
    }

    override fun toString(): String = "QueryStreams(chunkSize=$chunkSize, maxHold=$maxHold)"

    companion object {
        /** How long a stream runs before it ends to be resumed, by default. */
        val DEFAULT_MAX_HOLD: Duration = 30.seconds

        /** The event that ends a stream the server ended, or one that read every record. */
        const val END: String = "end"

        /** The stream read every record: not resumable. */
        const val COMPLETE: String = "complete"

        /** The stream ran for the hold time: resume it. */
        const val HOLD_EXPIRED: String = "hold-expired"

        /** The stream's store had no query permit for its next chunk in time: resume it later. */
        const val PERMIT_RECLAIMED: String = "permit-reclaimed"

        /** The node is stopping: resume the stream, on another node. */
        const val NODE_STOPPING: String = "node-stopping"
    }
}
