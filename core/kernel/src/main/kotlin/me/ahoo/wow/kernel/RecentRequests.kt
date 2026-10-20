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

package me.ahoo.wow.kernel

import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventStreamRecord

/**
 * The requests an aggregate committed most recently, at most [capacity] of them (decision D261): derived from the log
 * when the aggregate is loaded and kept beside its cached state, never stored. Each is a 64-bit hash of its request id
 * and the version it committed, in two primitive rings (12 bytes a request), so a hit only names the version whose
 * stream the kernel reads to confirm the request (a hash may collide) and to answer it as a duplicate.
 *
 * [complete] says it holds every request the aggregate ever committed: a miss then proves the request is not the
 * aggregate's. It holds at most [capacity], so it stops being complete once an older request is left out, read or
 * evicted.
 *
 * Not thread-safe: it lives in the [CachedState] one command at a time has taken.
 */
internal class RecentRequests private constructor(private val capacity: Int) {
    private val hashes = LongArray(capacity)
    private val versions = IntArray(capacity)

    /** The ring's slot of the oldest request. */
    private var start = 0

    var size = 0
        private set

    var complete = true
        private set

    /** The version that committed a request whose id hashes as [requestId]'s, the latest; null when none did. */
    fun versionOf(requestId: String): Int? {
        val hash = hashOf(requestId)
        for (offset in size - 1 downTo 0) {
            val slot = (start + offset) % capacity
            if (hashes[slot] == hash) {
                return versions[slot]
            }
        }
        return null
    }

    /** Adds the request [requestId] committed at [version], newer than every one held, evicting the oldest. */
    fun add(requestId: String, version: Int) {
        if (size == capacity) {
            start = (start + 1) % capacity
            size--
            complete = false
        }
        val slot = (start + size) % capacity
        hashes[slot] = hashOf(requestId)
        versions[slot] = version
        size++
    }

    companion object {
        /**
         * The recent requests of an aggregate whose history the kernel read: [earlier], the request keys of its streams
         * before [streams], latest first, as [me.ahoo.wow.spi.log.EventLog.readTail] gives them (empty when [streams]
         * start at version 1), and [streams] in version order. [reachedStart] says nothing is left before [earlier].
         */
        fun of(
            capacity: Int,
            earlier: List<CommittedRequest>,
            streams: List<EventStreamRecord>,
            reachedStart: Boolean,
        ): RecentRequests {
            require(capacity >= 1) { "Recent requests hold at least one request." }
            val recent = RecentRequests(capacity)
            val total = earlier.size + streams.size
            recent.complete = reachedStart && total <= capacity
            val skip = (total - capacity).coerceAtLeast(0)
            var index = 0
            for (key in earlier.asReversed()) {
                if (index++ >= skip) recent.add(key.requestId, key.version)
            }
            for (stream in streams) {
                if (index++ >= skip) recent.add(stream.requestId, stream.version)
            }
            return recent
        }

        /**
         * The 64-bit FNV-1a hash of [requestId]'s characters, finalized with the murmur3 mixer: a collision only costs
         * a read of the version it names and a lookup by request id.
         */
        fun hashOf(requestId: String): Long {
            var hash = FNV_OFFSET
            for (char in requestId) {
                hash = (hash xor char.code.toLong()) * FNV_PRIME
            }
            hash = (hash xor (hash ushr MIX_SHIFT)) * MIX_1
            hash = (hash xor (hash ushr MIX_SHIFT)) * MIX_2
            return hash xor (hash ushr MIX_SHIFT)
        }

        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L
        private const val MIX_SHIFT = 33
        private const val MIX_1 = -0xae502812aa7333L
        private const val MIX_2 = -0x3b314601e57a13adL
    }
}
