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

package me.ahoo.wow.mongo.log

import com.mongodb.client.MongoCollection
import java.util.concurrent.TimeUnit

/**
 * The bound of one append's round trips, [millis] (`append-timeout`) in all, from the call's start, so that a command
 * appending during an outage is answered before its wait ends (#6744): the reservation of a block, the read of the
 * aggregate's previous stream, the inserts, the lookups after a refusal and the placeholders, alone or in a group. The
 * time an append waits for its partition's sequencer, behind another append's reservation, counts too. It starts once
 * the call's collections are made sure of, the first use of a type being the schema's, under the schema's bounds.
 *
 * Each round trip takes what is left of the bound, at least a millisecond, which no round trip fits in.
 */
internal class AppendDeadline(private val millis: Long) {
    /** When the call started, by [System.nanoTime]. */
    private val started = System.nanoTime()

    /** [handle] for the append's next round trip: bounded by what is left of [millis]. */
    fun <T : Any> of(handle: MongoCollection<T>): MongoCollection<T> {
        val left = millis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        return handle.withTimeout(left.coerceAtLeast(1), TimeUnit.MILLISECONDS)
    }
}
