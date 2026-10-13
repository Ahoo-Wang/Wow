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
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.contextOf
import me.ahoo.wow.runtime.node.ContextNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.subscription.EntryDecoder
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
 *   commands' ids, so a wait chain may go on after their hop (decision D62).
 * - **Failures**: a handler that throws, or a state that cannot be read, is retried, then parks its aggregate, as any
 *   subscription ([me.ahoo.wow.runtime.subscription.SubscriptionOptions.retry]).
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
        processors[context].orEmpty().associate { handler ->
            handler.name to read(handler, models).mapTo(LinkedHashSet()) { it.type }
        }

    override fun subscriptions(context: ContextNode): List<Subscription> {
        val bound = processors[context.context].orEmpty()
        if (bound.isEmpty()) {
            return emptyList()
        }
        val derived = DerivedCommands(context.models, context.coordinator)
        return bound.map { handler ->
            val name = handler.name
            val filter = SubscriptionFilter(
                context.context,
                read(handler, context.models).mapTo(LinkedHashSet()) { it.type.name },
            )
            val decoder = EntryDecoder.stateEvents(context.stateReader(name))
            if (handler.kind == FunctionKind.SAGA) {
                Subscription.saga(name, filter, handler.startFrom, decoder, context.models) { entry ->
                    val event = stateEventOf(entry, context)
                    val commands = call(handler, event, contextOf(handler.kind, name, event))
                    derived.execute(commands, DerivedCommands.waitOf(entry.transport?.wait, context.context, name))
                }
            } else {
                Subscription(name, filter, handler.startFrom, decoder, context.models, handler.kind) { entry ->
                    val event = stateEventOf(entry, context)
                    call(handler, event, contextOf(handler.kind, name, event))
                }
            }
        }
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
        private val BLOCKING: CoroutineDispatcher by lazy {
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
         * (the aggregate's after the commit, decision D56), causation and headers, its decoded events, and the state
         * after the commit decoded afresh from its view, so no handler sees another's changes nor the live instance.
         */
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
            )
        }
    }
}
