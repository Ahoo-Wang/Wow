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

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord

/**
 * The write kernel: turns one admitted command into at most one committed event stream
 * (documentation/designs/v10/02-write-path.md §1).
 *
 * 1. **Deduplicate before deciding** (D46): a request id already committed in the tenant is never decided again; a
 *    different command reusing it is rejected.
 * 2. **Load** the committed state, **decide**, **evolve** a fresh state with the events, **append** once.
 * 3. A version conflict decides again on the newer state, up to [maxAttempts]; an unknown append outcome is settled
 *    by reading the version slot.
 *
 * Once an append commits, the rest of the attempt does not observe cancellation: a caller giving up must not tear
 * what is already a fact.
 */
class Kernel(
    private val log: EventLog,
    private val codec: EventCodec = EventCodec(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(maxAttempts >= 1) { "At least one attempt." }
    }

    suspend fun <S : Any> execute(model: AggregateModel<S>, command: Command<*>): CommandOutcome {
        require(command.ref.type == model.type) { "${command.ref} is not a ${model.type}." }
        val decider = model.decider(command.body.javaClass)
            ?: return rejected(KernelRejections.UNHANDLED, "${model.type} does not handle ${command.body.javaClass.name}.")
        val fingerprint = codec.fingerprint(command.body)
        log.findByRequestId(command.ref.tenantId, command.requestId)?.let { return settled(it, fingerprint) }
        repeat(maxAttempts) {
            val (state, version) = load(model, command)
            admissible(decider, command, version)?.let { return it }
            val events = when (val decision = decide(decider, state, command.body)) {
                is Decision.Events -> decision.events
                Decision.NoOp -> return CommandOutcome.Unchanged(version)
                is Decision.Rejected -> return CommandOutcome.Rejected(decision.rejection)
            }
            val evolved = events.fold(state) { current, event -> model.evolve(current, event) }
            val stream = EventStreamRecord(
                ref = command.ref,
                version = version + 1,
                commandId = command.id,
                requestId = command.requestId,
                fingerprint = fingerprint,
                commitTime = clock(),
                events = events.map { codec.encode(model, it) },
            )
            when (val appended = withContext(NonCancellable) { append(stream) }) {
                is AppendOutcome.Committed -> return CommandOutcome.Committed(stream, appended.position, evolved)
                is AppendOutcome.AlreadyCommitted -> return settled(appended.existing, fingerprint)
                is AppendOutcome.Conflict -> Unit
                is AppendOutcome.Unknown -> return CommandOutcome.Unknown(appended.cause)
            }
        }
        return CommandOutcome.Contended(maxAttempts)
    }

    /**
     * Appends [stream]; when the outcome is unknown, reads the version slot it targeted, which only one stream can
     * ever hold (the 9.3 rule): ours means committed, another means a conflict, none stays unknown.
     */
    private suspend fun append(stream: EventStreamRecord): AppendOutcome {
        val outcome = log.append(stream)
        if (outcome !is AppendOutcome.Unknown) {
            return outcome
        }
        val slot = try {
            log.read(stream.ref, stream.version, stream.version).firstOrNull()
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever stops the read, the outcome stays unknown.
            failure: Exception,
        ) {
            outcome.cause.addSuppressed(failure)
            return outcome
        }
        return when {
            slot == null -> outcome
            slot.requestId == stream.requestId -> AppendOutcome.AlreadyCommitted(slot)
            else -> AppendOutcome.Conflict(stream.version)
        }
    }

    private suspend fun <S : Any> load(model: AggregateModel<S>, command: Command<*>): Pair<S, Int> {
        val streams = log.read(command.ref).toList()
        var state = model.initialState(command.ref)
        streams.forEach { stream ->
            stream.events.forEach { record -> state = model.evolve(state, codec.decode(model, record)) }
        }
        return state to (streams.lastOrNull()?.version ?: 0)
    }

    private fun admissible(decider: DecideFunction<*>, command: Command<*>, version: Int): CommandOutcome? = when {
        version == 0 && !decider.create ->
            rejected(KernelRejections.NOT_FOUND, "${command.ref} does not exist.")

        version > 0 && decider.create ->
            rejected(KernelRejections.EXISTS, "${command.ref} already exists.")

        command.expectedVersion != null && command.expectedVersion != version ->
            rejected(
                KernelRejections.VERSION_MISMATCH,
                "${command.ref} is at version $version, not ${command.expectedVersion}.",
            )

        else -> null
    }

    private suspend fun <S : Any> decide(decider: DecideFunction<S>, state: S, body: Any): Decision = when (decider) {
        is DecideFunction.Sync -> decider.decide(state, body)
        is DecideFunction.Suspending -> decider.decide(state, body)
    }

    private fun settled(existing: EventStreamRecord, fingerprint: String): CommandOutcome =
        if (existing.fingerprint == fingerprint) {
            CommandOutcome.AlreadyCommitted(existing)
        } else {
            rejected(
                KernelRejections.REQUEST_ID_REUSED,
                "Request ${existing.requestId} was already used by another command.",
            )
        }

    private fun rejected(code: String, message: String) = CommandOutcome.Rejected(Rejection(code, message))

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
