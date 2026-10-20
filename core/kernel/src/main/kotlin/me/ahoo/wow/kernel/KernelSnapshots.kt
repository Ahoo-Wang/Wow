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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import tools.jackson.core.JacksonException

// How the kernel reads an aggregate's snapshot: only a cache, so a store that fails or a snapshot that does not fit
// costs a replay of the log, never the command.

private val logger = KotlinLogging.logger {}

/**
 * The snapshot of [ref], or null when there is none or the store fails: a snapshot is only a cache, so a failing
 * store costs a full replay, never the command. Cancellation still propagates.
 */
internal suspend fun SnapshotStore?.loadOrNull(ref: AggregateRef): SnapshotRecord? {
    val store = this ?: return null
    return try {
        store.load(ref)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (
        @Suppress("TooGenericExceptionCaught") // Whatever the store throws, the log still holds the state.
        failure: Exception,
    ) {
        logger.warn(failure) { "Loading the snapshot of $ref failed; replaying its log." }
        null
    }
}

/**
 * The state [snapshot] holds, decoded, or null when it does not fit: written at another state revision (decision
 * D172) or for another state schema, or not decodable.
 */
internal fun <S : Any> EventCodec.fitting(model: AggregateModel<S>, snapshot: SnapshotRecord): S? {
    if (snapshot.stateRevision != model.stateRevision) {
        logger.debug { "The snapshot of ${snapshot.ref} is of state revision ${snapshot.stateRevision}; replaying its log." }
        return null
    }
    if (snapshot.schemaFingerprint != stateSchemaFingerprint(model)) {
        logger.debug { "The snapshot of ${snapshot.ref} has another state schema; replaying its log." }
        return null
    }
    return try {
        decodeState(model, snapshot.state)
    } catch (failure: JacksonException) {
        logger.warn(failure) { "The snapshot of ${snapshot.ref} does not decode; replaying its log." }
        null
    } catch (failure: IllegalArgumentException) {
        logger.warn(failure) { "The snapshot of ${snapshot.ref} does not decode whole; replaying its log." }
        null
    }
}
