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
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.call.DecidingCommand
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.observe
import tools.jackson.core.JacksonException
import java.util.concurrent.ConcurrentHashMap

/**
 * How the [Kernel] turns a decision on a loaded state into what it commits ([prepare]): the stream to append and the
 * state after it, or the answer to a decision that appends nothing.
 *
 * [KernelOptions.verifyDecidePurity] makes the kernel fail a command whose decide function or after hook changed the state: with
 * mutable state nothing else stops that, and a change that bypasses events silently drifts from the log. The
 * test DSL turns it on; it costs two serializations of the state per command. A fresh state (version 0) with a
 * `lateinit` property still unassigned cannot be written, and is not checked: the creation event's sourcing assigns
 * it, and no state is written before that.
 */
internal class Preparation(
    private val codec: EventCodec,
    private val observer: Observer,
    private val options: KernelOptions,
    private val decisions: Decisions,
) {
    /**
     * [state] as decide receives it, for the purity check; null when it is fresh ([version] 0) and cannot be written
     * because a `lateinit` property is still unassigned, which the creation event's sourcing assigns. Any other failure
     * to write it fails the command.
     */
    private fun purityBaseline(state: Any, version: Int): String? = try {
        codec.stateText(state)
    } catch (failure: JacksonException) {
        val unassigned = generateSequence<Throwable>(failure) { it.cause }
            .any { it is UninitializedPropertyAccessException }
        if (version == 0 && unassigned) null else throw failure
    }

    /** Decides, runs the after hooks and evolves: either an outcome needing no append, or the stream to append. */
    internal suspend fun <S : Any> prepare(
        model: AggregateModel<S>,
        decider: DecideFunction<S>,
        scope: AttemptScope,
        loaded: CachedState,
        command: Command<*>,
        commandType: String,
        reach: CommandReach,
    ): Prepared {
        @Suppress("UNCHECKED_CAST") // The ref names the aggregate type, so the loaded state is this model's.
        val state = loaded.state as S
        val version = loaded.version
        val before = if (options.verifyDecidePurity) purityBaseline(state, version) else null
        // A command a client sends while deciding takes a request id derived from this one's, anew each attempt.
        val deciding = DecidingCommand(command.ref, command.requestId)
        val decision = observer.deciding(command).observe {
            withContext(deciding) { decisions.decideAll(model, decider, scope, state, command.body) }
        }
        // Checked for every decision, a rejection that writes nothing included: its state is cached again as it is.
        if (before != null) {
            check(codec.stateText(state) == before) {
                "Deciding ${command.body.javaClass.name} changed the state of ${command.ref}: only events may change it."
            }
        }
        rejectedUnwritten(decision, model, commandType, version, command)?.let { return it }
        // A rejection's events are committed as any are, the rejection recorded on their stream.
        val rejection = (decision as? Decision.Rejected)?.let {
            RejectionCodes.qualified(it.rejection, model.type, commandType)
        }
        val domain = decision.events
        // A rejection commits its own events alone: what the refused decision scheduled is not.
        val schedules = if (rejection == null) scope.scheduleEvents() else emptyList()
        val events = domain + schedules
        if (events.isEmpty()) {
            return Prepared.Done(CommandOutcome.Unchanged(version))
        }
        check(domain.isNotEmpty() || version > 0) {
            "${command.ref} does not exist: a decision that only schedules needs an aggregate that exists."
        }
        // The decision's own time: what it read as `now` is the time its events are committed at.
        return appended(model, loaded, command, commandType, events, rejection, scope.now, reach)
    }

    /**
     * The stream [command] appends with [events] (a rejection's when [rejection] is not null) on [loaded] at
     * [commitTime], and the state after it; refused ([Prepared.Done.evolved]) when it leaves the aggregate out of its
     * caller's reach ([leaves]), checked once the events evolved the state, on the attributes the stream records.
     */
    private fun <S : Any> appended(
        model: AggregateModel<S>,
        loaded: CachedState,
        command: Command<*>,
        commandType: String,
        events: List<Any>,
        rejection: Rejection?,
        commitTime: Long,
        reach: CommandReach,
    ): Prepared {
        val version = loaded.version

        @Suppress("UNCHECKED_CAST") // The ref names the aggregate type, so the loaded state is this model's.
        val state = loaded.state as S
        val metadata = metadataAfter(model, loaded, command, events)
        // Read before the events evolve the state, which may change it in place.
        val before = if (reach.governed) loaded.declared(model) else emptyMap()
        val evolved = evolveDecided(model, state, events, command, commandType, version + 1, commitTime, metadata.scope)
        val declared = model.accessOf(evolved)
        leaves(command, reach, version, metadata.scope, before, declared)?.let {
            return Prepared.Done(it, evolved = true)
        }
        val stream = EventStreamRecord(
            ref = command.ref,
            version = version + 1,
            commandId = command.id,
            requestId = command.requestId,
            commandType = commandType,
            commitTime = commitTime,
            events = events.map { codec.encode(model, it) },
            scope = metadata.scope,
            causation = command.causation,
            headers = rejection?.let { command.headers + EventStreamRecord.rejectionHeaders(it) } ?: command.headers,
            operator = command.operator,
            access = AccessValues.ofStream(codec.stored(declared), metadata.scope),
            // The attributes after this commit are of its own version, until a later change stamps them.
            accessVersion = version + 1,
        )
        val after = loaded.with(evolved, stream.version, metadata)
        after.access = declared
        if (stream.version == 1) {
            after.first = FirstCommit.of(stream)
        }
        return Prepared.Append(stream, after)
    }

    /**
     * [state] evolved by [events], decided on [command] and committed at [version] and [commitTime] in [scope]: an
     * evolve function that reads its commit reads it as the log will record it, so a rebuild reads the same.
     */
    private fun <S : Any> evolveDecided(
        model: AggregateModel<S>,
        state: S,
        events: List<Any>,
        command: Command<*>,
        commandType: String,
        version: Int,
        commitTime: Long,
        scope: Scope,
    ): S {
        val commit = if (model.readsCommits) {
            model.commitOf(
                command.ref,
                version,
                commitTime,
                command.id,
                command.requestId,
                commandType,
                scope,
                command.operator,
                events,
            )
        } else {
            null
        }
        return events.fold(state) { current, event -> model.evolve(current, event, commit) }
    }

    /**
     * The aggregate's metadata after [command] commits [events] on [loaded]. Its scope: a command that
     * creates the aggregate gives it the caller's scope ([createdIn]); every other command carries the aggregate's
     * scope forward, and only the lifecycle events of transferring the owner or the space change it. Its deleted mark
     * changes only by its lifecycle events too.
     */
    private fun metadataAfter(
        model: AggregateModel<*>,
        loaded: CachedState,
        command: Command<*>,
        events: List<Any>,
    ): KernelLifecycle.Metadata {
        val before = if (loaded.version == 0) {
            loaded.metadata.copy(scope = createdIn(model, command))
        } else {
            loaded.metadata
        }
        return events.fold(before, KernelLifecycle::evolve)
    }

    /**
     * The answer to [decision] when it is a rejection that writes nothing: one without events, at the [version] the
     * decision saw, or one of a creation, whose aggregate does not exist ([creationRejectedWithEvents]); null
     * otherwise. A local code is named by the deciding command's aggregate type from here on.
     */
    private fun rejectedUnwritten(
        decision: Decision,
        model: AggregateModel<*>,
        commandType: String,
        version: Int,
        command: Command<*>,
    ): Prepared.Done? {
        if (decision !is Decision.Rejected || decision.events.isNotEmpty() && version > 0) return null
        val rejection = RejectionCodes.qualified(decision.rejection, model.type, commandType)
        observer.rejected(model.type, commandType, rejection, command, withEvents = false)
        val outcome = CommandOutcome.Rejected(
            rejection,
            version.takeIf { it > 0 },
            droppedEvents = decision.events.size
        )
        if (outcome.droppedEvents > 0) creationRejectedWithEvents(commandType)
        return Prepared.Done(outcome)
    }

    /** The command types whose creation was rejected with events, warned about once each. */
    private val creationRejections: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * A creation of [commandType] rejected with events: its aggregate does not exist, so nothing is written and the
     * events are dropped ([CommandOutcome.Rejected.droppedEvents]), which the test DSL refuses; warned about once per
     * command type.
     */
    private fun creationRejectedWithEvents(commandType: String) {
        if (creationRejections.add(commandType)) {
            logger.warn {
                "A creation of $commandType was rejected with events: its aggregate does not exist, so they are " +
                    "dropped and nothing is written. Reject it without events."
            }
        }
    }
}

internal sealed interface Prepared {
    /**
     * An outcome that appends nothing; [evolved] when it is a refusal once the decision's events evolved the
     * state ([leaves]), which may have changed it in place.
     */
    class Done(val outcome: CommandOutcome, val evolved: Boolean = false) : Prepared

    class Append(val stream: EventStreamRecord, val evolved: CachedState) : Prepared
}

/** The domain events this decision commits: none for a no-op, a rejection's own with it. */
private val Decision.events: List<Any>
    get() = when (this) {
        is Decision.Events -> events
        Decision.NoOp -> emptyList()
        is Decision.Rejected -> events
    }

private val logger = KotlinLogging.logger(Kernel::class.java.name)
