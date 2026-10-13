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

import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.ProjectContext
import me.ahoo.wow.api.subscribe.ReactContext
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext

/**
 * One handler of an annotated subscription processor, bound to its processor instance (design 04 §1, decisions D37,
 * D149): what the compiler generates for each `@OnEvent` function of a `@Projection`, `@Reactor` or `@Saga`, exposed
 * by its module's [ModelProvider.processors]. It knows no runtime: the runtime compiles each into one subscription
 * named [name], `processor.function`.
 *
 * - [stateType] is the state of the `StateEvent<S>` the handler takes: the subscription reads the aggregates of its
 *   context whose state is one, and hands each commit over with the state after it. Null for `StateEvent<*>`: every
 *   aggregate of the context. [nullableState] says the handler takes a null state, `StateEvent<S?>` or
 *   `StateEvent<*>` (decision D140): what a stream another context publishes without its state needs.
 * - [aggregates] are those `@OnEvent(aggregates)` names, `context.aggregate`, all of one context (decision D138). When
 *   they are of another context than the module's ([sourceContext]), the handler reads that context's published
 *   streams instead of the log (decision D134): its state, if any, is decoded from the published state into
 *   [stateType], and its events are the published ones.
 * - [startFrom] is where the subscription starts in a partition it has no checkpoint in yet: by default a
 *   projection from the beginning of the log, so it builds its read model from the whole history, and a reactor or a
 *   saga from its end (04 §3: a new subscription chooses), so deploying one does not replay side effects or derive
 *   commands for the history.
 * - [blocking] (`@Blocking`, design 01 §4) asks the runtime to run the call on virtual threads instead of inline.
 * - [retry] (`@Retry` on the handler or its processor, roadmap P.3) is the handler's own in-process retry budget,
 *   under what the host configures for its subscription and over the host's global one.
 * - [invoke] calls the handler directly and returns the commands a saga derives; a projection's or a reactor's none.
 * - [rejections] are a saga's `@OnRejected` handlers (decisions D136, D153), the same for each handler of the saga:
 *   the runtime hands the rejection of a command this handler derived to the one [rejectionHandlerOf] its class.
 *
 * Built with [project], [react] or [saga], one per kind.
 */
class ProcessorBinding<S> private constructor(
    /** The processor's name: its `@Name`, or its class's simple name. */
    val processor: String,
    /** The handler function's name. */
    val function: String,
    val kind: FunctionKind,
    val stateType: Class<S & Any>?,
    val startFrom: StartFrom,
    val blocking: Boolean,
    val rejections: List<RejectionBinding<*>>,
    val aggregates: List<String>,
    val nullableState: Boolean,
    /** The handler's own in-process retry budget (`@Retry`, roadmap P.3); null when it gives none. */
    val retry: HandlerRetry?,
    private val call: suspend (StateEvent<S>, SubscriptionContext) -> List<Command<*>>,
) {
    init {
        require(processor.isNotBlank()) { "A handler names its processor." }
        require(function.isNotBlank()) { "A handler names its function." }
        require(
            aggregates.all { AGGREGATE.matches(it) }
        ) { "$processor.$function reads $aggregates: each is context.aggregate." }
        require(aggregates.map { it.substringBefore('.') }.distinct().size <= 1) {
            "$processor.$function reads the aggregates of more than one context: $aggregates."
        }
        rejections.forEach {
            require(it.processor == processor) { "$it is not a handler of $processor." }
        }
        rejections.groupBy { it.commandType }.filterValues { it.size > 1 }.keys.forEach {
            throw IllegalArgumentException(
                "$processor has two @OnRejected handlers of the rejections of ${it?.name ?: "every command"}.",
            )
        }
    }

    /**
     * The `@OnRejected` handler of the saga for the rejection of a command of [command] this handler derived
     * ([RejectionBinding.handlerOf]); null when the saga has none for it.
     */
    fun rejectionHandlerOf(command: Class<*>): RejectionBinding<*>? = RejectionBinding.handlerOf(rejections, command)

    /** The subscription the handler is compiled into: `processor.function` (design 04 §1). */
    val name: String = "$processor.$function"

    /** The context of the [aggregates] the handler names; null when it names none: its module's. */
    val sourceContext: String? get() = aggregates.firstOrNull()?.substringBefore('.')

    /** Whether the handler reads the published streams of a context other than [context], its module's (D134). */
    fun isSourced(context: String): Boolean = sourceContext.let { it != null && it != context }

    /**
     * Calls the handler with [event], whose state is a [stateType], and the [context] of its kind ([contextOf]), in
     * the caller's coroutine: running a [blocking] one elsewhere is the runtime's.
     */
    @Suppress("UNCHECKED_CAST") // The subscription reads only the aggregates whose state is a stateType.
    suspend fun invoke(event: StateEvent<*>, context: SubscriptionContext): List<Command<*>> =
        call(event as StateEvent<S>, context)

    override fun toString(): String = "ProcessorBinding($name, $kind)"

    companion object {
        private val AGGREGATE = Regex("[^.\\s]+\\.[^.\\s]+")

        /** A projection starts from the beginning of the log; a reactor or a saga from its end. */
        @JvmStatic
        fun defaultStartFrom(kind: FunctionKind): StartFrom =
            if (kind == FunctionKind.PROJECT) StartFrom.BEGINNING else StartFrom.END

        /** A `@Projection`'s handler [function] of [processor]. */
        @JvmStatic
        @Suppress("LongParameterList")
        fun <S> project(
            processor: String,
            function: String,
            stateType: Class<S & Any>?,
            blocking: Boolean = false,
            startFrom: StartFrom = defaultStartFrom(FunctionKind.PROJECT),
            aggregates: List<String> = emptyList(),
            nullableState: Boolean = stateType == null,
            retry: HandlerRetry? = null,
            handle: suspend (StateEvent<S>, ProjectContext) -> Unit,
        ): ProcessorBinding<S> =
            ProcessorBinding(
                processor,
                function,
                FunctionKind.PROJECT,
                stateType,
                startFrom,
                blocking,
                emptyList(),
                aggregates,
                nullableState,
                retry,
            ) { e, c ->
                handle(e, c as ProjectContext)
                emptyList()
            }

        /** A `@Reactor`'s handler [function] of [processor]. */
        @JvmStatic
        @Suppress("LongParameterList")
        fun <S> react(
            processor: String,
            function: String,
            stateType: Class<S & Any>?,
            blocking: Boolean = false,
            startFrom: StartFrom = defaultStartFrom(FunctionKind.REACT),
            aggregates: List<String> = emptyList(),
            nullableState: Boolean = stateType == null,
            retry: HandlerRetry? = null,
            handle: suspend (StateEvent<S>, ReactContext) -> Unit,
        ): ProcessorBinding<S> =
            ProcessorBinding(
                processor,
                function,
                FunctionKind.REACT,
                stateType,
                startFrom,
                blocking,
                emptyList(),
                aggregates,
                nullableState,
                retry,
            ) { e, c ->
                handle(e, c as ReactContext)
                emptyList()
            }

        /**
         * A `@Saga`'s handler [function] of [processor]: it returns the commands it derives ([derivedOf]); the saga's
         * `@OnRejected` handlers, the [rejections], take the rejections of those commands.
         */
        @JvmStatic
        @Suppress("LongParameterList")
        fun <S> saga(
            processor: String,
            function: String,
            stateType: Class<S & Any>?,
            blocking: Boolean = false,
            startFrom: StartFrom = defaultStartFrom(FunctionKind.SAGA),
            rejections: List<RejectionBinding<*>> = emptyList(),
            aggregates: List<String> = emptyList(),
            nullableState: Boolean = stateType == null,
            retry: HandlerRetry? = null,
            handle: suspend (StateEvent<S>, SagaContext) -> List<Command<*>>,
        ): ProcessorBinding<S> =
            ProcessorBinding(
                processor,
                function,
                FunctionKind.SAGA,
                stateType,
                startFrom,
                blocking,
                rejections,
                aggregates,
                nullableState,
                retry,
            ) { e, c ->
                handle(e, c as SagaContext)
            }
    }
}

/** Where a subscription without a checkpoint in a partition starts. */
enum class StartFrom {
    /** From the first entry of the partition: a new projection, or a replay under a new name. */
    BEGINNING,

    /** After the entries already committed when the subscription first starts in the partition (its log head). */
    END,
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
