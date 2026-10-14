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
import me.ahoo.wow.api.subscribe.CallRejectedException
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.contextOf
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.runtime.call.SagaCalls
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.command.ReceiptHandler
import me.ahoo.wow.runtime.node.ContextNode
import me.ahoo.wow.runtime.node.SourcedSubscription
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.subscription.EntryDecoder
import me.ahoo.wow.runtime.subscription.HandledEntry
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.wait.WaitContext
import java.util.concurrent.Executors

/**
 * Adapts the handlers of annotated processors ([ProcessorBinding], decision D149) into the subscriptions of a
 * [me.ahoo.wow.runtime.node.ServiceNode]: one per handler (design 04 §1), in the bounded context its processor's
 * module belongs to, by context in [processors].
 *
 * - **Name, filter and start**: the subscription is the handler's [ProcessorBinding.name], `processor.function`, of
 *   the aggregates of the context whose state is the handler's state type (every aggregate for `StateEvent<*>`), of
 *   any of their events: a handler's signature names its state, not its events. It starts where the handler says
 *   ([ProcessorBinding.startIn]: as `@OnEvent(start)` declares, else a projection or a handler of another context's
 *   stream from the beginning, a reactor or a saga of the context's own log from the end; decision D194). A state type no aggregate of the context has fails the node's assembly.
 * - **What the handler gets**: the entry decoded by [EntryDecoder.stateEvents] (decision D133), each subscription
 *   the state reader of its own name ([stateReaders]): its events decoded and upgraded, and the state after the
 *   commit, a copy decoded from the context's shared view for this handler alone; with the context of its kind, whose
 *   [me.ahoo.wow.api.subscribe.ExecutionId] is (subscription, aggregate, version).
 * - **Calls**: in the subscription's coroutine, or on a virtual thread for a `@Blocking` handler
 *   ([ProcessorBinding.blocking]).
 * - **Sagas** execute what they derive through the context's wait coordinator ([DerivedCommands]) and report the
 *   commands' ids, so a wait chain may go on after their hop (decision D62). A command to another context is sent to
 *   its command topic (decisions D33, D137), asking for a receipt of its rejection when the saga has an `@OnRejected`
 *   or an `@OnCommitted` handler for it (decision D136), and of its commit when it has the latter (decision D192);
 *   [receipts] hands those receipts to them.
 * - **Rejections** of what a saga derives are business outcomes (decision D153), neither retried nor, when the saga
 *   handles them, parking: the first rejected command stops the execution, and the wait at the saga's hop ends
 *   REJECTED with its error ([HandledEntry.rejection]). The saga's `@OnRejected` handler for the command, if any, gets it
 *   ([SagaReceipts], which executes what it derives to compensate); without one, the aggregate parks at once
 *   ([DerivedCommandRejectedException]).
 * - **Commits** of what a saga derives in its own context go to the saga's `@OnCommitted` handler of the command, if
 *   any (decision D192), right after the commands are executed, in their order; what it derives is executed too, but
 *   is not among the commands the saga's hop reports: the wait follows the committed command, not the receipt.
 * - **Schedules** a saga's handler makes ([me.ahoo.wow.api.subscribe.SagaContext.schedule], decision D198) are derived
 *   commands too, the system commands `wow.schedule.Set` and `wow.schedule.Cancel` to their target, executed in order
 *   with the others; the command they schedule is named by the context's models and Manifests
 *   ([ContextNode.commandTypes]). A target that does not exist rejects one, which goes to `@OnRejected`.
 * - **Jobs** (decision D198): a saga's `@OnSchedule` handler ([ProcessorBinding.job]) is a saga handler of the
 *   context's built-in job aggregate, `<context>.schedule`, which the node adds for the [jobs] its handlers run: each
 *   tick of its job is handed to it, and what it derives is executed as any saga's.
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

    override fun jobs(context: String): List<ScheduleJob> = processors[context].orEmpty().mapNotNull { it.job }

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
        val receipts = receiptsOf(context, bound)
        val derived = DerivedCommands(context.models, context.coordinator, context.sender, context.creations)
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
                    handler.startIn(context.context),
                    decoder,
                    context.models,
                    retry = handler.retry
                ) { entry ->
                    handleSaga(handler, stateEventOf(entry, context), entry, context, derived, receipts)
                }
            } else {
                Subscription(
                    name,
                    filter,
                    handler.startIn(context.context),
                    decoder,
                    context.models,
                    handler.kind,
                    retry = handler.retry,
                ) { entry ->
                    val event = stateEventOf(entry, context)
                    call(handler, event, contextOf(handler.kind, name, event, context.ids, context.commandTypes))
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
        val receipts = receiptsOf(context, bound)
        val derived = DerivedCommands(context.models, context.coordinator, context.sender, context.creations)
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
                    handler.startIn(context.context),
                    context = context.context,
                    retry = handler.retry
                ) { entry ->
                    handleSaga(handler, publishedStateEventOf(entry, handler, context), entry, context, derived, receipts)
                }
            } else {
                Subscription(
                    name,
                    filter,
                    handler.startIn(context.context),
                    kind = handler.kind,
                    context = context.context,
                    retry = handler.retry,
                ) { entry ->
                    val event = publishedStateEventOf(entry, handler, context)
                    call(handler, event, contextOf(handler.kind, name, event, context.ids, context.commandTypes))
                }
            }
            SourcedSubscription(subscription, types)
        }
    }

    /**
     * The `@OnRejected` and `@OnCommitted` handlers of [context]'s sagas, for the receipts of the commands they sent to
     * other contexts (decisions D136, D192); null when none of its sagas has one.
     */
    override fun receipts(context: ContextNode): ReceiptHandler? =
        receiptsOf(context, processors[context.context].orEmpty()).takeUnless { it.isEmpty }

    /** The names of the `@OnCommitted` handlers of [context]'s sagas, each a hop of its waits (decision D192). */
    override fun committedHandlers(context: String): Set<String> =
        processors[context].orEmpty().flatMapTo(LinkedHashSet()) { saga -> saga.commits.map { it.name } }

    /** The receipt handlers of the sagas of [bound], of [context], signalling their hops through its coordinator. */
    private fun receiptsOf(context: ContextNode, bound: List<ProcessorBinding<*>>): SagaReceipts = SagaReceipts(
        bound,
        DerivedCommands(context.models, context.coordinator, context.sender, context.creations),
        context.ids,
        context.context,
        context.coordinator::signal,
    )

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
         * Hands [event], of [entry], to the saga [handler] of [context] and executes what it derives through [derived]
         * (design 04 §6.1). The wait the entry carries on past the saga's hop ([DerivedCommands.waitOf]) goes with its
         * derived commands and with its synchronous calls (decision D193), whose ids the saga's hop reports first, in
         * the order they were made. A call's rejection the handler lets through parks the aggregate at once
         * ([CallRejectedHandlingException]); a call that failed fails the handling, retried then parked.
         */
        internal suspend fun handleSaga(
            handler: ProcessorBinding<*>,
            event: StateEvent<*>,
            entry: SubscribedEntry,
            context: ContextNode,
            derived: DerivedCommands,
            receipts: SagaReceipts,
        ): HandledEntry {
            val name = handler.name
            val wait = DerivedCommands.waitOf(entry.transport?.wait, context.context, name)
            val calls = SagaCalls(context.caller, context.served, context.commandTypes, wait)
            val execution = contextOf(handler.kind, name, event, context.ids, context.commandTypes, calls)
            val commands = try {
                call(handler, event, execution)
            } catch (rejected: CallRejectedException) {
                throw CallRejectedHandlingException(rejected)
            }
            val replyTo = replyTo(handler, context.context, execution.executionId)
            val executed = derived.execute(commands, wait, replyTo)
            val withCalls = executed.copy(executed = calls.called + executed.executed)
            return sagaHandled(withCalls, execution.executionId, receipts, wait)
        }

        /**
         * What a saga's execution [executed] reports (decision D153): the commands it executed, or, when one was
         * rejected, those and what the saga's `@OnRejected` handler derived for it, with the rejection; a rejection
         * no handler takes parks the aggregate ([DerivedCommandRejectedException]). First, each command of its own
         * context it committed goes to the saga's `@OnCommitted` handler of it, if any (decision D192), with the
         * [wait] those commands carry: the handler's hop and its commands go on with that chain as a saga's do.
         */
        internal suspend fun sagaHandled(
            executed: DerivedCommands.Executed,
            execution: ExecutionId,
            receipts: SagaReceipts,
            wait: WaitContext? = null,
        ): HandledEntry {
            // The receipts carry the commands' chain on past their commit, through the @OnCommitted handler's hop.
            val next = wait?.afterCommitted()
            executed.committed.forEach { committed ->
                receipts.committed(committed.toCommandCommitted(execution), committed.command.body.javaClass, next)
            }
            val rejected = executed.rejected ?: return HandledEntry(executed.executed)
            val compensations = receipts.rejected(rejected.toCommandRejected(execution), rejected.command.body.javaClass)
                ?: throw DerivedCommandRejectedException(rejected.command, rejected.rejection)
            return HandledEntry(executed.executed + compensations, rejected.error)
        }

        /**
         * Where the receipts of a command the saga [handler] of [context] derived in its [execution] go, when another
         * context rejects or commits it (decisions D136, D192): back to the saga, when it has an `@OnRejected` or an
         * `@OnCommitted` handler for the command's class, asking for the receipt of the commit only with the latter;
         * otherwise nowhere, and the target only logs a rejection.
         */
        internal fun replyTo(
            handler: ProcessorBinding<*>,
            context: String,
            execution: ExecutionId,
        ): (Command<*>) -> ReplyTo? = { command ->
            val type = command.body.javaClass
            val committed = handler.committedHandlerOf(type) != null
            ReplyTo.of(context, execution, committed).takeIf { committed || handler.rejectionHandlerOf(type) != null }
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
