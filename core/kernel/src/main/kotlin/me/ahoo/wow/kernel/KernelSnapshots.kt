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
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.SnapshotTail
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.log.readSnapshotTailApart
import me.ahoo.wow.spi.log.readWithoutSnapshot
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import tools.jackson.core.JacksonException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

// How the kernel reads an aggregate's snapshot: only a cache, so a store that fails or a snapshot that does not fit
// costs a replay of the log, never the command.

private val logger = KotlinLogging.logger {}

/**
 * How a cache miss reads an aggregate's snapshot and the tail after it: in one round trip through the reader
 * [readers] gives its type, the one of a store holding both ([me.ahoo.wow.spi.store.Store.snapshotTails]), else apart
 * ([readSnapshotTailApart]). When the reader fails, the read is done apart. When it gives no answer within [timeout], it
 * is cancelled and the log is read alone ([readWithoutSnapshot]): the reader's store is the log's, so reading the
 * snapshot apart would wait on the same store again. Each is counted with [ObservedEvent.StoreFellBack], `failed` or
 * `timed-out`, and the first of each outage is logged as a warning ([OutageWarnings]).
 */
internal class SnapshotTailReads(
    private val log: EventLog,
    private val readers: (AggregateType) -> SnapshotTailReader?,
    private val observer: Observer,
    private val timeout: Duration = KernelOptions.DEFAULT_SNAPSHOT_LOAD_TIMEOUT,
) {
    private val warnings = OutageWarnings("a read answers")

    suspend fun read(ref: AggregateRef, snapshots: SnapshotStore, earlier: Int): SnapshotTail {
        val reader = readers(ref.type) ?: return log.readSnapshotTailApart(ref, snapshots, earlier)
        try {
            // Null only when this read's own deadline passed: a caller's cancellation is thrown on.
            withTimeoutOrNull(timeout) { reader.readWithSnapshot(ref, earlier) }?.let {
                warnings.answered()
                return it
            }
            fellBack(ref, ObservedEvent.StoreFellBack.Reason.TIMED_OUT)
            warnings.failed(null) {
                "Reading the snapshot of $ref with its tail in one round trip gave no answer within $timeout: " +
                    "replaying its log."
            }
            return log.readWithoutSnapshot(ref)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever the one read throws, the reads apart decide the outcome.
            failure: Exception,
        ) {
            fellBack(ref, ObservedEvent.StoreFellBack.Reason.FAILED)
            warnings.failed(failure) {
                "Reading the snapshot of $ref with its tail in one round trip failed: reading them apart."
            }
        }
        return log.readSnapshotTailApart(ref, snapshots, earlier)
    }

    private fun fellBack(ref: AggregateRef, reason: ObservedEvent.StoreFellBack.Reason) = observer.observed(
        ObservedEvent.StoreFellBack(
            ref.type.context,
            ObservedEvent.StoreFellBack.Capability.SNAPSHOT_TAIL_READ,
            reason
        ),
    )
}

/**
 * [store] as a cache-miss load reads it with the log ([SnapshotTailReads]): a snapshot that fails to load is none, a
 * snapshot being only a cache, so a failing store costs a full replay and only the log's failure fails the command.
 * A load that gives no answer within [timeout] is failed the same way: cancelled, its store call interrupted, and
 * counted as timed out, so a store that stops answering costs a replay, not the command's wait. Cancellation from
 * the caller still propagates. Each failure is counted with [ObservedEvent.StoreFellBack], and the first of each
 * outage is logged as a warning ([OutageWarnings]): a store that is down fails every load.
 */
internal class TolerantSnapshots(
    private val store: SnapshotStore,
    private val observer: Observer,
    private val timeout: Duration = KernelOptions.DEFAULT_SNAPSHOT_LOAD_TIMEOUT,
) : SnapshotStore by store {
    private val warnings = OutageWarnings("a load succeeds")

    override suspend fun load(ref: AggregateRef): SnapshotRecord? = try {
        // Null only when this load's own deadline passed: a caller's cancellation is thrown on.
        val loaded = withTimeoutOrNull(timeout) { Loaded(store.load(ref)) }
        if (loaded == null) {
            fellBack(ref, ObservedEvent.StoreFellBack.Reason.TIMED_OUT, null) {
                "Loading the snapshot of $ref gave no answer within $timeout; replaying its log."
            }
        } else {
            warnings.answered()
            loaded.snapshot
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (
        @Suppress("TooGenericExceptionCaught") // Whatever the store throws, the log still holds the state.
        failure: Exception,
    ) {
        fellBack(ref, ObservedEvent.StoreFellBack.Reason.FAILED, failure) {
            "Loading the snapshot of $ref failed; replaying its log."
        }
    }

    /** No snapshot: [failure] counted, and logged as a warning when it is this outage's first. */
    private fun fellBack(
        ref: AggregateRef,
        reason: ObservedEvent.StoreFellBack.Reason,
        failure: Exception?,
        message: () -> String,
    ): SnapshotRecord? {
        observer.observed(
            ObservedEvent.StoreFellBack(ref.type.context, ObservedEvent.StoreFellBack.Capability.SNAPSHOT_LOAD, reason),
        )
        warnings.failed(failure, message)
        return null
    }

    /** A load's answer, null when there is no snapshot: told apart from a load that gave none in time. */
    private class Loaded(val snapshot: SnapshotRecord?)
}

/**
 * The warnings of the outages of one capability of a store: the first failure of an outage is logged as a warning, the
 * later ones at debug level, until [until] (a call that answers, [answered]) ends it, so the next failure, a later
 * outage's first, is a warning again. Every failure is counted besides ([ObservedEvent.StoreFellBack]).
 */
internal class OutageWarnings(private val until: String) {
    private val warned = AtomicBoolean()

    /** A call answered: the outage, if any, is over. */
    fun answered() {
        if (warned.get()) {
            warned.set(false)
        }
    }

    /** A failure of the outage: [message] logged as a warning when it is the outage's first, else at debug level. */
    fun failed(failure: Exception?, message: () -> String) {
        if (warned.compareAndSet(false, true)) {
            logger.warn(failure) {
                "${message()} Later failures are counted (wow.store.fallbacks), and logged at debug level until $until."
            }
        } else {
            logger.debug(failure, message)
        }
    }
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
