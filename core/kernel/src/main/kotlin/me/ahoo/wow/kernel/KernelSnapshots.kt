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
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.SnapshotTail
import me.ahoo.wow.spi.log.readSnapshotTailApart
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.StoreFallbacks
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import tools.jackson.core.JacksonException
import java.util.concurrent.atomic.AtomicBoolean

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
 * How a cache miss reads an aggregate's snapshot and the tail after it: in one round trip through the
 * log's [EventLog.snapshotTails] when it has that capability, else apart ([readSnapshotTailApart]). When the reader
 * cannot serve the aggregate's snapshot store, or fails, the read is done apart: the first time logged (a failure as a
 * warning), every time counted with [Observer.storeFellBack].
 */
internal class SnapshotTailReads(private val log: EventLog, private val observer: Observer) {
    private val unavailableLogged = AtomicBoolean()
    private val failedLogged = AtomicBoolean()

    suspend fun read(ref: AggregateRef, snapshots: SnapshotStore, earlier: Int): SnapshotTail {
        val reader = log.snapshotTails ?: return log.readSnapshotTailApart(ref, snapshots, earlier)
        try {
            reader.readWithSnapshot(ref, snapshots, earlier)?.let { return it }
            fellBack(ref, StoreFallbacks.UNAVAILABLE)
            if (unavailableLogged.compareAndSet(false, true)) {
                logger.info {
                    "The log of ${ref.type.context} cannot read the snapshots of ${ref.type} with their tails in one " +
                        "round trip, as they are in another database: it reads them apart."
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever the one read throws, the reads apart decide the outcome.
            failure: Exception,
        ) {
            fellBack(ref, StoreFallbacks.FAILED)
            if (failedLogged.compareAndSet(false, true)) {
                logger.warn(failure) {
                    "Reading the snapshot of $ref with its tail in one round trip failed: reading them apart. Later " +
                        "failures are counted (wow.store.fallbacks), and logged at debug level."
                }
            } else {
                logger.debug(failure) { "Reading the snapshot of $ref with its tail in one round trip failed." }
            }
        }
        return log.readSnapshotTailApart(ref, snapshots, earlier)
    }

    private fun fellBack(ref: AggregateRef, reason: String) =
        observer.storeFellBack(ref.type.context, StoreFallbacks.SNAPSHOT_TAIL_READ, reason)
}

/**
 * [store] as a cache-miss load reads it with the log ([SnapshotTailReads]):
 * a snapshot that fails to load is none ([loadOrNull]), so only the log's failure fails the command.
 */
internal class TolerantSnapshots(private val store: SnapshotStore) : SnapshotStore by store {
    override suspend fun load(ref: AggregateRef): SnapshotRecord? = store.loadOrNull(ref)
}

/**
 * The state [snapshot] holds, decoded, or null when it does not fit: written at another state revision
 * or for another state schema, or not decodable.
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
