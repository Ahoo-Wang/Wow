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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.TransportCodec
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.observe

/**
 * How the [Kernel] appends a decided stream and settles what the append answered: the append observed, with what it
 * carries for delivery; an outcome that names this very append taken as committed, an unknown one settled by the
 * version slot it targeted, a conflict checked against the request key ([append]); and the command's outcome on
 * each, compensating an attempt whose events are surely not appended ([settleAppend]).
 */
internal class Appends(
    private val log: EventLog,
    private val observer: Observer,
) {
    /**
     * [appendObserved], compensating [attempt] when the append throws: a backend throws only what surely wrote
     * nothing, such as a store it could not reach before sending the append (the retryable storage error).
     */
    internal suspend fun appendCompensating(stream: EventStreamRecord, attempt: AttemptScope): AppendOutcome = try {
        appendObserved(stream, attempt)
    } catch (
        @Suppress("TooGenericExceptionCaught") // Compensates whatever failed, then rethrows it unchanged.
        failure: Throwable,
    ) {
        attempt.compensate(failure)
        throw failure
    }

    /** Appends [stream] of [attempt], observed: what it carries for delivery holds the append's trace. */
    private suspend fun appendObserved(stream: EventStreamRecord, attempt: AttemptScope): AppendOutcome {
        val appending = observer.appending(stream)
        return appending.observe {
            append(
                stream,
                attempt.transport(appending.trace, stream.rejection != null)?.let(TransportCodec::encode)
            ).also { appending.outcome(it.observedName) }
        }
    }

    /** The outcome of an attempt whose [stream] was [appended]; null on a version conflict, decided again. */
    internal suspend fun settleAppend(
        appended: AppendOutcome,
        stream: EventStreamRecord,
        evolved: CachedState,
        attempt: AttemptScope,
        commandType: CommandType,
        requests: RequestCheck,
    ): CommandOutcome? = when (appended) {
        is AppendOutcome.Committed -> {
            requests.committed(stream, evolved)
            val rejection = stream.rejection
            if (rejection == null) {
                withContext(NonCancellable) { attempt.afterAppend(stream.version) }
            } else {
                // The refused decision's reservations are released, as for a rejection that writes nothing.
                attempt.compensate()
                observer.rejected(stream.ref.type, commandType.name, rejection, attempt.command, withEvents = true)
            }
            CommandOutcome.Committed(
                stream,
                appended.position,
                evolved.state,
                if (rejection == null) attempt.results() else emptyMap(),
                evolved.deleted,
                evolved.schedules,
                evolved.first,
                rejection,
            )
        }

        is AppendOutcome.AlreadyCommitted -> {
            attempt.compensate()
            // Answered as the request-id lookup answers it: within the scope that sent it. With the recent
            // requests, a request id reused on another aggregate of the type reaches only this key.
            requests.duplicateOf(appended.existing)
        }

        is AppendOutcome.Conflict -> {
            attempt.compensate()
            // Decided again only once compensated, and only while the caller still waits.
            currentCoroutineContext().ensureActive()
            null
        }

        is AppendOutcome.Unknown -> CommandOutcome.Unknown(appended.cause)
        is AppendOutcome.Rejected -> {
            // Nothing was committed and nothing will be: compensated, and failed as a refusing backend always failed.
            attempt.compensate()
            throw appended.cause
        }
    }

    /** [outcome] of [stream]'s append, settled as [append] settles it. */
    internal suspend fun settled(stream: EventStreamRecord, outcome: AppendOutcome): AppendOutcome = when (outcome) {
        is AppendOutcome.Unknown -> settleUnknown(stream, outcome)
        is AppendOutcome.AlreadyCommitted -> duplicateOf(stream, outcome)
        is AppendOutcome.Conflict -> settleConflict(stream, outcome)
        else -> outcome
    }

    /**
     * Appends [stream] with [transport]. An outcome that names this very append (the same command, request and commit
     * time at the same version) is this append committed: when the outcome is unknown, the version slot it targeted,
     * which only one stream can ever hold (the 9.3 rule), decides it (ours means committed, another stream of the
     * request a duplicate, another request a conflict, none stays unknown); and a duplicate the backend reports may be
     * its own retry of this append. Treating either as a duplicate would compensate what is a fact.
     * A conflict is checked against the request key before the command is decided again: a backend that
     * compares the version first reports a retry of the request at the same version as a conflict, and deciding it
     * again would run the decision's I/O twice.
     */
    private suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
        settled(stream, log.append(stream, transport))

    /** [duplicate] of [stream]: [stream] itself committed when it names this very append. */
    private suspend fun duplicateOf(
        stream: EventStreamRecord,
        duplicate: AppendOutcome.AlreadyCommitted
    ): AppendOutcome =
        if (duplicate.existing.isAppendOf(stream)) committedAt(stream, duplicate) else duplicate

    /**
     * The [conflict] of [stream], unless its request is committed: then a duplicate, or this append itself. A failing
     * lookup leaves the conflict, decided again as before.
     */
    private suspend fun settleConflict(stream: EventStreamRecord, conflict: AppendOutcome.Conflict): AppendOutcome {
        val existing = try {
            log.findByRequestId(stream.ref, stream.requestId)
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever stops the lookup, the conflict stands.
            failure: Exception,
        ) {
            logger.debug(failure) { "The request of ${stream.ref}@${stream.version} could not be looked up again." }
            null
        }
        return existing?.let { duplicateOf(stream, AppendOutcome.AlreadyCommitted(it)) } ?: conflict
    }

    /** Reads the version slot an [unknown] append of [stream] targeted; a failing read leaves it unknown. */
    private suspend fun settleUnknown(stream: EventStreamRecord, unknown: AppendOutcome.Unknown): AppendOutcome {
        val slot = try {
            log.readEntries(stream.ref, stream.version, stream.version).firstOrNull()
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever stops the read, the outcome stays unknown.
            failure: Exception,
        ) {
            unknown.cause.addSuppressed(failure)
            return unknown
        }
        return when {
            slot == null -> unknown
            slot.stream.isAppendOf(stream) -> AppendOutcome.Committed(slot.position)
            slot.stream.requestId == stream.requestId -> AppendOutcome.AlreadyCommitted(slot.stream)
            else -> AppendOutcome.Conflict(stream.version)
        }
    }

    /**
     * [stream], which the backend reported [duplicate] of itself, committed at its position. Committed it is, so it is
     * never compensated: when its position cannot be read, the outcome is unknown, which compensates nothing either.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever stops the read, the commit stands; its outcome is unknown.
    private suspend fun committedAt(
        stream: EventStreamRecord,
        duplicate: AppendOutcome.AlreadyCommitted
    ): AppendOutcome {
        val position = try {
            log.positionOf(stream.ref, stream.version)
        } catch (failure: Exception) {
            return AppendOutcome.Unknown(failure)
        }
        val existing = duplicate.existing
        return position?.let { AppendOutcome.Committed(it) } ?: AppendOutcome.Unknown(
            IllegalStateException("${existing.ref}@${existing.version} is committed without a position."),
        )
    }

    /** Whether this committed stream is the append of [stream] itself, not another append of its request. */
    private fun EventStreamRecord.isAppendOf(stream: EventStreamRecord): Boolean =
        ref == stream.ref &&
            version == stream.version &&
            commandId == stream.commandId &&
            requestId == stream.requestId &&
            commitTime == stream.commitTime
}

private val logger = KotlinLogging.logger(Kernel::class.java.name)

/** How an append ended, as its observation names it. */
internal val AppendOutcome.observedName: ObservedOutcome
    get() = when (this) {
        is AppendOutcome.Committed -> ObservedOutcome.COMMITTED
        is AppendOutcome.AlreadyCommitted -> ObservedOutcome.DUPLICATE
        is AppendOutcome.Conflict -> ObservedOutcome.CONFLICT
        is AppendOutcome.Unknown -> ObservedOutcome.UNKNOWN
        is AppendOutcome.Rejected -> ObservedOutcome.REJECTED
    }
