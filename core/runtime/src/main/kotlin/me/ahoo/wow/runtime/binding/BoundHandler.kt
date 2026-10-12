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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.ProjectContext
import me.ahoo.wow.api.subscribe.ReactContext
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext
import java.util.concurrent.Executors

/**
 * One handler of an annotated subscription processor, bound to its processor instance (design 04 §1, decision D37):
 * what the compiler generates for each `@OnEvent` function of a `@Projection`, `@Reactor` or `@Saga`, and what
 * [SubscriptionBindings] compiles into one subscription named [name], `processor.function`.
 *
 * - [stateType] is the state of the `StateEvent<S>` the handler takes: the subscription reads the aggregates of its
 *   context whose state is one, and hands each commit over with the state after it. Null for `StateEvent<*>`: every
 *   aggregate of the context.
 * - [blocking] (`@Blocking`, design 01 §4) dispatches the call to virtual threads instead of running it inline.
 * - The call returns the commands a saga derives; a projection's or a reactor's none.
 *
 * Built with [project], [react] or [saga], one per kind.
 */
class BoundHandler<S : Any> private constructor(
    /** The processor's name: its `@Name`, or its class's simple name. */
    val processor: String,
    /** The handler function's name. */
    val function: String,
    val kind: FunctionKind,
    val stateType: Class<S>?,
    val blocking: Boolean,
    private val call: suspend (StateEvent<S>, SubscriptionContext) -> List<Command<*>>,
) {
    init {
        require(processor.isNotBlank()) { "A handler names its processor." }
        require(function.isNotBlank()) { "A handler names its function." }
    }

    /** The subscription the handler is compiled into: `processor.function` (design 04 §1). */
    val name: String = "$processor.$function"

    /**
     * Calls the handler with [event], whose state is a [stateType], and the [context] of its kind; on virtual threads
     * when it is [blocking].
     */
    @Suppress("UNCHECKED_CAST") // The subscription reads only the aggregates whose state is a stateType.
    suspend fun handle(event: StateEvent<*>, context: SubscriptionContext): List<Command<*>> {
        val typed = event as StateEvent<S>
        return if (blocking) withContext(BLOCKING) { call(typed, context) } else call(typed, context)
    }

    override fun toString(): String = "BoundHandler($name, $kind)"

    companion object {
        /** Runs the `@Blocking` handlers: a virtual thread per call (decision D29: JDK 21). */
        private val BLOCKING: CoroutineDispatcher by lazy {
            Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
        }

        /** A `@Projection`'s handler [function] of [processor]. */
        @JvmStatic
        fun <S : Any> project(
            processor: String,
            function: String,
            stateType: Class<S>?,
            blocking: Boolean = false,
            handle: suspend (StateEvent<S>, ProjectContext) -> Unit,
        ): BoundHandler<S> = BoundHandler(processor, function, FunctionKind.PROJECT, stateType, blocking) { e, c ->
            handle(e, c as ProjectContext)
            emptyList()
        }

        /** A `@Reactor`'s handler [function] of [processor]. */
        @JvmStatic
        fun <S : Any> react(
            processor: String,
            function: String,
            stateType: Class<S>?,
            blocking: Boolean = false,
            handle: suspend (StateEvent<S>, ReactContext) -> Unit,
        ): BoundHandler<S> = BoundHandler(processor, function, FunctionKind.REACT, stateType, blocking) { e, c ->
            handle(e, c as ReactContext)
            emptyList()
        }

        /** A `@Saga`'s handler [function] of [processor]: it returns the commands it derives ([derivedOf]). */
        @JvmStatic
        fun <S : Any> saga(
            processor: String,
            function: String,
            stateType: Class<S>?,
            blocking: Boolean = false,
            handle: suspend (StateEvent<S>, SagaContext) -> List<Command<*>>,
        ): BoundHandler<S> = BoundHandler(processor, function, FunctionKind.SAGA, stateType, blocking) { e, c ->
            handle(e, c as SagaContext)
        }
    }
}

/**
 * The commands a saga's handler returned (design 01 §1): nothing (`null`, `Unit`, an empty `Mono`), one [Command], or
 * an iterable of them, such as the list a `Flux` was collected into. Anything else is a handler that does not return
 * commands, which fails it.
 */
fun derivedOf(result: Any?): List<Command<*>> = when (result) {
    null, Unit -> emptyList()
    is Command<*> -> listOf(result)
    is Iterable<*> -> result.map { element ->
        element as? Command<*> ?: throw IllegalStateException("A saga derives commands, not $element.")
    }

    else -> throw IllegalStateException("A saga derives commands, not $result.")
}
