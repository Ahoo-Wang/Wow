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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.SubscriptionContext
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.runtime.node.ContextNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.subscription.EntryDecoder
import me.ahoo.wow.runtime.subscription.StartFrom
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter

/**
 * Registers the handlers of annotated processors ([BoundHandler]) in a [me.ahoo.wow.runtime.node.ServiceNode]: one
 * subscription per handler (design 04 §1), in the bounded context its processor's module belongs to, by context in
 * [handlers].
 *
 * - **Name and filter**: the subscription is the handler's [BoundHandler.name], `processor.function`, of the
 *   aggregates of the context whose state is the handler's state type (every aggregate for `StateEvent<*>`), of any of
 *   their events: a handler's signature names its state, not its events. A state type no aggregate of the context
 *   has fails the node's assembly.
 * - **What the handler gets**: the entry's events decoded through the context's models with the kernel's codec, the
 *   state after the commit ([StateEventReader]; the node keeps the state views of the aggregates read here,
 *   [viewTypes]), and the context of its kind, whose [ExecutionId] is (subscription, aggregate, version).
 * - **Sagas** execute what they derive through the context's wait coordinator ([DerivedCommands]) and report the
 *   commands' ids, so a wait chain may go on after their hop (decision D62).
 * - **Failures**: a handler that throws, or a state that cannot be read, is retried, then parks its aggregate, as any
 *   subscription ([me.ahoo.wow.runtime.subscription.SubscriptionOptions.retry]).
 * - **Start**: a new projection starts from the beginning of the log, so it builds its read model from the whole
 *   history; a new reactor or saga from its end (04 §3: a new subscription chooses), so deploying one does not replay
 *   side effects or derive commands for the history ([startFrom]).
 */
class SubscriptionBindings(
    private val handlers: Map<String, List<BoundHandler<*>>>,
    private val startFrom: (BoundHandler<*>) -> StartFrom = ::defaultStartFrom,
) : SubscriptionFactory {
    init {
        handlers.forEach { (context, bound) ->
            bound.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
                throw IllegalArgumentException("Context $context has two handlers named $it.")
            }
        }
    }

    override fun viewTypes(context: String, models: List<AggregateModel<*>>): Set<AggregateType> =
        handlers[context].orEmpty().flatMapTo(LinkedHashSet()) { handler -> read(handler, models).map { it.type } }

    override fun subscriptions(context: ContextNode): List<Subscription> {
        val bound = handlers[context.context].orEmpty()
        if (bound.isEmpty()) {
            return emptyList()
        }
        val reader = StateEventReader(
            context.models,
            context.kernel,
            context.codec,
            context.views,
            context.backends.snapshots,
        )
        val decoder = EntryDecoder.models(context.models, context.codec)
        val derived = DerivedCommands(context.models, context.coordinator)
        return bound.map { handler ->
            val name = handler.name
            val filter = SubscriptionFilter(
                context.context,
                read(handler, context.models).mapTo(LinkedHashSet()) { it.type.name },
            )
            if (handler.kind == FunctionKind.SAGA) {
                Subscription.saga(name, filter, startFrom(handler), decoder, context.models) { entry ->
                    val event = reader.read(entry)
                    val commands = handler.handle(
                        event,
                        RuntimeSagaContext(ExecutionId(name, event.ref, event.version), event)
                    )
                    derived.execute(commands, DerivedCommands.waitOf(entry.transport?.wait, context.context, name))
                }
            } else {
                Subscription(name, filter, startFrom(handler), decoder, context.models, handler.kind) { entry ->
                    val event = reader.read(entry)
                    handler.handle(event, contextOf(handler.kind, ExecutionId(name, event.ref, event.version)))
                }
            }
        }
    }

    /** The models of [models] whose state [handler] reads. */
    private fun read(handler: BoundHandler<*>, models: List<AggregateModel<*>>): List<AggregateModel<*>> {
        val type = handler.stateType ?: return models
        return models.filter { type.isAssignableFrom(it.stateType) }.also {
            require(it.isNotEmpty()) {
                "${handler.name} reads the state ${type.name}, which no aggregate of its context " +
                    "(${models.joinToString { model -> model.type.toString() }}) has."
            }
        }
    }

    private fun contextOf(kind: FunctionKind, execution: ExecutionId): SubscriptionContext =
        if (kind == FunctionKind.REACT) RuntimeReactContext(execution) else RuntimeProjectContext(execution)

    companion object {
        /** No handlers. */
        @JvmField
        val NONE: SubscriptionBindings = SubscriptionBindings(emptyMap())

        /**
         * The handlers of [providers] (by default those registered on the class path), their processors constructed
         * with [dependencies]: a [me.ahoo.wow.kernel.MissingDependencyException] when one is missing, so the node is
         * not built.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            dependencies: Dependencies,
            providers: List<SubscriptionProvider> = SubscriptionProvider.load(),
        ): SubscriptionBindings = SubscriptionBindings(
            providers.groupBy { it.context }.mapValues { (_, inContext) ->
                inContext.flatMap { it.handlers(dependencies) }
            },
        )

        /** A projection starts from the beginning of the log; a reactor or a saga from its end. */
        @JvmStatic
        fun defaultStartFrom(handler: BoundHandler<*>): StartFrom =
            if (handler.kind == FunctionKind.PROJECT) StartFrom.BEGINNING else StartFrom.END
    }
}
