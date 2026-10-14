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

package me.ahoo.wow.runtime.binding

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.contextOf
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.command.RejectionHandler
import me.ahoo.wow.runtime.node.ContextNode
import me.ahoo.wow.runtime.node.SourcedSubscription
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.subscription.EntryDecoder
import me.ahoo.wow.runtime.subscription.HandledEntry
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import java.util.concurrent.Executors

/**
 * Adapts the handlers of annotated processors ([ProcessorBinding], decision D149) into the subscriptions of a
 * [me.ahoo.wow.runtime.node.ServiceNode]: one per handler (design 04 §1), in the bounded context its processor's
 * module belongs to, by context in [processors].
 *
 * - **Name, filter and start**: the subscription is the handler's [ProcessorBinding.name], `processor.function`, of
 *   the aggregates of the context whose state is the handler's state type (every aggregate for `StateEvent<*>`), of
 *   any of their events: a handler's signature names its state, not its events. It starts where the handler says
 *   ([ProcessorBinding.startFrom]). A state type no aggregate of the context has fails the node's assembly.
 * - **What the handler gets**: the entry decoded by [EntryDecoder.stateEvents] (decision D133), each subscription
 *   the state reader of its own name ([stateReaders]): its events decoded and upgraded, and the state after the
 *   commit, a copy decoded from the context's shared view for this handler alone; with the context of its kind, whose
 *   [me.ahoo.wow.api.subscribe.ExecutionId] is (subscription, aggregate, version).
 * - **Calls**: in the subscription's coroutine, or on a virtual thread for a `@Blocking` handler
 *   ([ProcessorBinding.blocking]).
 * - **Sagas** execute what they derive through the context's wait coordinator ([DerivedCommands]) and report the
 *   commands' ids, so a wait chain may go on after their hop (decision D62). A command to another context is sent to
 *   its command topic (decisions D33, D137), asking for a receipt of its rejection when the saga has an `@OnRejected`
 *   handler for it (decision D136); [rejections] hands those receipts to it.
 * - **Rejections** of what a saga derives are business outcomes (decision D153), neither retried nor, when the saga
 *   handles them, parking: the first rejected command stops the execution, and the wait at the saga's hop ends
 *   REJECTED with its error ([HandledEntry.rejection]). The saga's `@OnRejected` handler for the command, if any, gets it
 *   ([SagaRejections], which executes what it derives to compensate); without one, the aggregate parks at once
 *   ([DerivedCommandRejectedException]).
 * - **Schedules** a saga's handler makes ([me.ahoo.wow.api.subscribe.SagaContext.schedule], decision D166) are written
 *   to the context's timer store ([me.ahoo.wow.runtime.timer.SagaTimers]) before the commands it derived execute, so
 *   before its checkpoint passes the commit; handling it again writes the same timers.
 * - **Failures**: a handler that throws, or a state that cannot be read, is retried, then parks its aggregate, as any
 *   subscription ([me.ahoo.wow.runtime.subscription.SubscriptionOptions.retry]); a handler's `@Retry`
 *   ([ProcessorBinding.retry], roadmap P.3) is its subscription's own budget ([Subscription.retry]).
 * - **Other contexts' streams** (decisions D134, D138): a handler whose `@OnEvent(aggregates)` names another context's
 *   ([ProcessorBinding.isSourced]) is [sourced]: its subscription reads the published streams of those aggregates
 *   from their topics. It gets the record's facts and published events ([me.ahoo.wow.spi.state.StateEventPayload], each
 *   its name, revision and JSON payload), and the published state decoded into its state type, or null when the
 *   upstream publishes without it (decision D140), which only a handler of a nullable state takes. It reads no state
 *   view of its own context. It belongs to its own context ([Subscription.context]): a wait the record carries names
 *   its hop there, and it signals that hop to the wait's origin as a subscription of its own context would (design 04
 *   §6.3); a saga's commands carry the rest of the chain on ([DerivedCommands.waitOf]).
 */
class SubscriptionBindings(private val processors: Map<String, List<ProcessorBinding<*>>>) : SubscriptionFactory {
    init {
        processors.forEach { (context, bound) ->
            bound.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
                throw IllegalArgumentException("Context $context has two handlers named $it.")
            }
        }
    }

    override fun viewTypes(context: String, models: List<AggregateModel<*>>): Set<AggregateType> =
        stateReaders(context, models).values.flatMapTo(LinkedHashSet()) { it }

    override fun stateReaders(context: String, models: List<AggregateModel<*>>): Map<String, Set<AggregateType>> =
        processors[context].orEmpty().filterNot { it.isSourced(context) }.associate { handler ->
            handler.name to read(handler, models).mapTo(LinkedHashSet()) { it.type }
        }

    override fun subscriptions(context: ContextNode): List<Subscription> {
        val bound = processors[context.context].orEmpty()
        val local = bound.filterNot { it.isSourced(context.context) }
        if (local.isEmpty()) {
            return emptyList()
        }
        val derived = DerivedCommands(context.models, context.coordinator, context.sender, context.creations)
        val rejections = SagaRejections(bound, derived, context.ids)
        return local.map { handler ->
            val name = handler.name
            val filter = SubscriptionFilter(
                context.context,
                read(handler, context.models).mapTo(LinkedHashSet()) { it.type.name },
            )
            val decoder = EntryDecoder.stateEvents(context.stateReader(name))
            if (handler.kind == FunctionKind.SAGA) {
                Subscription.saga(
                    name,
                    filter,
                    handler.startFrom,
                    decoder,
                    context.models,
                    retry = handler.retry
                ) { entry ->
                    val event = stateEventOf(entry, context)
                    val execution = contextOf(handler.kind, name, event, context.ids)
                    val commands = call(handler, event, execution)
                    writeSchedules(context, name, event, execution)
                    val wait = DerivedCommands.waitOf(entry.transport?.wait, context.context, name)
                    val replyTo = replyTo(handler, context.context, execution.executionId)
                    sagaHandled(derived.execute(commands, wait, replyTo), execution.executionId, rejections)
                }
            } else {
                Subscription(
                    name,
                    filter,
                    handler.startFrom,
                    decoder,
                    context.models,
                    handler.kind,
                    retry = handler.retry,
                ) { entry ->
                    val event = stateEventOf(entry, context)
                    call(handler, event, contextOf(handler.kind, name, event, context.ids))
                }
            }
        }
    }

    override fun sourced(context: ContextNode): List<SourcedSubscription> {
        val bound = processors[context.context].orEmpty()
        val sourced = bound.filter { it.isSourced(context.context) }
        if (sourced.isEmpty()) {
            return emptyList()
        }
        val derived = DerivedCommands(context.models, context.coordinator, context.sender, context.creations)
        val rejections = SagaRejections(bound, derived, context.ids)
        return sourced.map { handler ->
            val name = handler.name
            val types = handler.aggregates.mapTo(LinkedHashSet()) {
                AggregateType(it.substringBefore('.'), it.substringAfter('.'))
            }
            val filter = SubscriptionFilter(handler.sourceContext!!, types.mapTo(LinkedHashSet()) { it.name })
            val subscription = if (handler.kind == FunctionKind.SAGA) {
                Subscription.saga(
                    name,
                    filter,
                    handler.startFrom,
                    context = context.context,
                    retry = handler.retry
                ) { entry ->
                    val event = publishedStateEventOf(entry, handler, context)
                    val execution = contextOf(handler.kind, name, event, context.ids)
                    val commands = call(handler, event, execution)
                    writeSchedules(context, name, event, execution)
                    val wait = DerivedCommands.waitOf(entry.transport?.wait, context.context, name)
                    val replyTo = replyTo(handler, context.context, execution.executionId)
                    sagaHandled(derived.execute(commands, wait, replyTo), execution.executionId, rejections)
                }
            } else {
                Subscription(
                    name,
                    filter,
                    handler.startFrom,
                    kind = handler.kind,
                    context = context.context,
                    retry = handler.retry,
                ) { entry ->
                    val event = publishedStateEventOf(entry, handler, context)
                    call(handler, event, contextOf(handler.kind, name, event, context.ids))
                }
            }
            SourcedSubscription(subscription, types)
        }
    }

    /**
     * The `@OnRejected` handlers of [context]'s sagas, for the receipts of the commands they sent to other contexts
     * (decision D136); null when none of its sagas has one.
     */
    override fun rejections(context: ContextNode): RejectionHandler? {
        val bound = processors[context.context].orEmpty()
        val derived = DerivedCommands(context.models, context.coordinator, context.sender, context.creations)
        return SagaRejections(bound, derived, context.ids).takeUnless { it.isEmpty }
    }

    /** The models of [models] whose state [handler] reads. */
    private fun read(handler: ProcessorBinding<*>, models: List<AggregateModel<*>>): List<AggregateModel<*>> {
        val type = handler.stateType ?: return models
        return models.filter { type.isAssignableFrom(it.stateType) }.also {
            require(it.isNotEmpty()) {
                "${handler.name} reads the state ${type.name}, which no aggregate of its context " +
                    "(${models.joinToString { model -> model.type.toString() }}) has."
            }
        }
    }

    companion object {
        /** No handlers. */
        @JvmField
        val NONE: SubscriptionBindings = SubscriptionBindings(emptyMap())

        /** Runs the `@Blocking` handlers: a virtual thread per call (decision D29: JDK 21). */
        internal val BLOCKING: CoroutineDispatcher by lazy {
            Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
        }

        /**
         * The handlers of the processors of [providers] (by default the binding indexes on the class path, decision
         * D107), their processors constructed with [dependencies]: a [me.ahoo.wow.kernel.MissingDependencyException]
         * when one is missing, so the node is not built.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            dependencies: Dependencies,
            providers: List<ModelProvider> = ModelProvider.load(),
        ): SubscriptionBindings = SubscriptionBindings(
            providers.groupBy { it.context }
                .mapValues { (_, inContext) -> inContext.flatMap { it.processors(dependencies) } }
                .filterValues { it.isNotEmpty() },
        )

        /**
         * What a saga's execution [executed] reports (decision D153): the commands it executed, or, when one was
         * rejected, those and what the saga's `@OnRejected` handler derived for it, with the rejection; a rejection
         * no handler takes parks the aggregate ([DerivedCommandRejectedException]).
         */
        internal suspend fun sagaHandled(
            executed: DerivedCommands.Executed,
            execution: ExecutionId,
            rejections: SagaRejections,
        ): HandledEntry {
            val rejected = executed.rejected ?: return HandledEntry(executed.executed)
            val compensations = rejections.handle(rejected.toCommandRejected(execution), rejected.command.body.javaClass)
                ?: throw DerivedCommandRejectedException(rejected.command, rejected.rejection)
            return HandledEntry(executed.executed + compensations, rejected.error)
        }

        /**
         * Where the receipt of a command the saga [handler] of [context] derived in its [execution] goes, when another
         * context rejects it (decision D136): back to the saga, when it has an `@OnRejected` handler for the
         * command's class; otherwise nowhere, and the target only logs the rejection.
         */
        internal fun replyTo(
            handler: ProcessorBinding<*>,
            context: String,
            execution: ExecutionId,
        ): (Command<*>) -> ReplyTo? = { command ->
            handler.rejectionHandlerOf(command.body.javaClass)?.let { ReplyTo.of(context, execution) }
        }

        /**
         * Writes the schedules the saga [subscription] made in its [execution] of [event] to [context]'s timer store
         * (decision D166); a context without one fails the handling.
         */
        internal suspend fun writeSchedules(
            context: ContextNode,
            subscription: String,
            event: StateEvent<*>,
            execution: SubscriptionContext,
        ) {
            val schedules = (execution as? ExecutionSagaContext)?.schedules.orEmpty()
            if (schedules.isEmpty()) {
                return
            }
            val timers = checkNotNull(context.timers) {
                "$subscription schedules commands, and context ${context.context} has no timer store (decision D166)."
            }
            timers.sagas.write(subscription, event, schedules)
        }

        /** Calls [handler] with [event] and [context]: on a virtual thread when it is blocking. */
        internal suspend fun call(
            handler: ProcessorBinding<*>,
            event: StateEvent<*>,
            context: SubscriptionContext,
        ): List<Command<*>> = if (handler.blocking) {
            withContext(BLOCKING) { handler.invoke(event, context) }
        } else {
            handler.invoke(event, context)
        }

        /**
         * The [StateEvent] of [entry] (design 04 §1, design 08 §5): the stream's identity, command, commit time, scope
         * (the aggregate's after the commit, decision D56), causation, headers and operator, its decoded events, and the state
         * after the commit decoded afresh from its view, so no handler sees another's changes nor the live instance.
         */
        /**
         * The [StateEvent] of [entry], a record read from another context's topic (decision D134): its facts, its
         * published events, and its published state decoded into the [handler]'s state type (the JSON tree for
         * `StateEvent<*>`), or null when the record carries none: published without it (decision D140), which a
         * handler of a state that is not nullable cannot take.
         */
        internal fun publishedStateEventOf(
            entry: SubscribedEntry,
            handler: ProcessorBinding<*>,
            context: ContextNode,
        ): StateEvent<Any?> {
            val record = checkNotNull(entry.published) { "${entry.ref}@${entry.version} was not read from a topic." }
            val view = record.state
            check(view != null || handler.nullableState) {
                "${record.id} carries no state, but ${handler.name} takes a state that is not nullable."
            }
            val type = handler.stateType
            val state: Any? = view?.let { if (type == null) it.state else context.codec.decodeTree(it.state, type) }
            return StateEvent(
                ref = record.ref,
                version = record.version,
                commandId = record.commandId,
                requestId = record.requestId,
                commandType = record.commandType,
                commitTime = record.commitTime,
                events = record.events,
                state = state,
                scope = record.scope,
                causation = record.causation,
                headers = record.headers,
                operator = record.operator,
            )
        }

        private suspend fun stateEventOf(entry: SubscribedEntry, context: ContextNode): StateEvent<Any> {
            val stream = entry.stream
            val model = context.models.first { it.type == stream.ref.type }
            val view = entry.state()
            return StateEvent(
                ref = stream.ref,
                version = stream.version,
                commandId = stream.commandId,
                requestId = stream.requestId,
                commandType = stream.commandType,
                commitTime = stream.commitTime,
                events = entry.events,
                state = context.codec.decodeState(model, view.state),
                scope = stream.scope,
                causation = stream.causation,
                headers = stream.headers,
                operator = stream.operator,
            )
        }
    }
}
