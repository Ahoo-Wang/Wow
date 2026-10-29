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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.schedule.ScheduleTick
import me.ahoo.wow.api.schedule.Ticked
import me.ahoo.wow.api.subscribe.JobContext
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext
import me.ahoo.wow.kernel.schedule.JobState
import me.ahoo.wow.kernel.schedule.ScheduleJob

/**
 * One handler of an annotated subscription processor, bound to its processor instance:
 * what the compiler generates for each `@OnEvent` function of a `@Projection`, `@Reactor` or `@Saga`, exposed
 * by its module's [ModelProvider.processors]. It knows no runtime: the runtime compiles each into one subscription
 * named [name], `processor.function`.
 *
 * - [stateType] is the state of the `StateEvent<S>` the handler takes: the subscription reads the aggregates of its
 *   context whose state is one, and hands each commit over with the state after it. Null for `StateEvent<*>`: every
 *   aggregate of the context. [nullableState] says the handler takes a null state, `StateEvent<S?>` or
 *   `StateEvent<*>`: what a stream another context publishes without its state needs.
 * - [aggregates] are those `@OnEvent(aggregates)` names, `context.aggregate`, all of one context. When
 *   they are of another context than the module's ([sourceContext]), the handler reads that context's published
 *   streams instead of the log: its state, if any, is decoded from the published state into
 *   [stateType], and its events are the published ones.
 * - [startFrom] is where the subscription starts in a partition it has no checkpoint in yet, as `@OnEvent(start)`
 *   declares it; null leaves it to [startIn]: a projection from the beginning, so it builds its read
 *   model from the whole history, and so does any handler of another context's stream, so a service that joins later
 *   still gets what that context published before; a reactor or a saga of its own context's log from its end
 *   (a new subscription chooses), so deploying one does not replay side effects or derive commands for the history.
 * - [blocking] (`@Blocking`) asks the runtime to run the call on virtual threads instead of inline.
 * - [retry] (`@Retry` on the handler or its processor) is the handler's own in-process retry budget,
 *   under what the host configures for its subscription and over the host's global one.
 * - [invoke] calls the handler directly and returns the commands a saga derives; a projection's or a reactor's none.
 * - [rejections] are a saga's `@OnRejected` handlers, the same for each handler of the saga:
 *   the runtime hands the rejection of a command this handler derived to the one [rejectionHandlerOf] its class.
 * - [commits] are a saga's `@OnCommitted` handlers, likewise: the runtime hands the success receipt of
 *   a command this handler derived to the one [committedHandlerOf] its class, and only such a command asks for one.
 *
 * - [job] is the job of a saga's `@OnSchedule` handler ([schedule]): its subscription reads the
 *   context's built-in job aggregate (`<context>.schedule`, [JobState]) and hands each tick of its job over as a
 *   [ScheduleTick].
 *
 * Built with [project], [react] or [saga], one per kind, or [schedule] for a saga's job.
 */
class ProcessorBinding<S> private constructor(
    /** The processor's name: its `@Name`, or its class's simple name. */
    val processor: String,
    /** The handler function's name. */
    val function: String,
    val kind: FunctionKind,
    val stateType: Class<S & Any>?,
    /** Where `@OnEvent(start)` says the subscription starts; null when it leaves that to [startIn]. */
    val startFrom: StartFrom?,
    val blocking: Boolean,
    val rejections: List<RejectionBinding<*>>,
    val aggregates: List<String>,
    val nullableState: Boolean,
    /** The handler's own in-process retry budget (`@Retry`); null when it gives none. */
    val retry: HandlerRetry?,
    val commits: List<CommittedBinding<*>>,
    /** The job of an `@OnSchedule` handler ([schedule]); null for an `@OnEvent` one. */
    val job: ScheduleJob? = null,
    /**
     * The event type a handler of each event of a type takes: a stream without one is acknowledged
     * without being handed over, nor its state decoded. Null for a handler of the whole stream.
     */
    val eventType: Class<*>? = null,
    /**
     * The command classes a saga's handler declares it calls with `SagaContext.call` (`@OnEvent(calls)`):
     * the only ones its calls take. Empty for any other handler.
     */
    val calls: List<Class<*>> = emptyList(),
    /**
     * The bounded context whose job aggregate an `@OnSchedule` handler's [job] runs in, as the compiler that generated
     * the binding knew it (`wow.context`, else the module's `@BoundedContext`); null when it knew none, and for an
     * `@OnEvent` handler, which the runtime binds in its context.
     */
    val context: String? = null,
    private val call: suspend (StateEvent<S>, SubscriptionContext) -> List<CommandRequest<*>>,
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
        commits.forEach {
            require(it.processor == processor) { "$it is not a handler of $processor." }
        }
        commits.groupBy { it.commandType }.filterValues { it.size > 1 }.keys.forEach {
            throw IllegalArgumentException(
                "$processor has two @OnCommitted handlers of the receipts of ${it?.name ?: "every command"}.",
            )
        }
    }

    /**
     * The `@OnRejected` handler of the saga for the rejection of a command of [command] this handler derived
     * ([RejectionBinding.handlerOf]); null when the saga has none for it.
     */
    fun rejectionHandlerOf(command: Class<*>): RejectionBinding<*>? = RejectionBinding.handlerOf(rejections, command)

    /**
     * The `@OnCommitted` handler of the saga for the success receipt of a command of [command] this handler derived
     * ([CommittedBinding.handlerOf]); null when the saga has none for it, and the command asks for no receipt.
     */
    fun committedHandlerOf(command: Class<*>): CommittedBinding<*>? = CommittedBinding.handlerOf(commits, command)

    /**
     * Whether [events], a stream's decoded events, hold none this handler takes: a handler of each event of an
     * [eventType] skips a stream without one.
     */
    fun skips(events: List<Any>): Boolean = eventType != null && events.none { eventType.isInstance(it) }

    /**
     * This handler reading [aggregates], `context.aggregate`: those of another context whose events it takes by type
     * without naming them, which the service's Manifest finds from the peer contracts.
     */
    @InternalWowApi
    fun reading(aggregates: List<String>): ProcessorBinding<S> = ProcessorBinding(
        processor, function, kind, stateType, startFrom, blocking, rejections, aggregates, nullableState, retry,
        commits, job, eventType, calls, context, call,
    )

    /** The subscription the handler is compiled into: `processor.function`. */
    val name: String = "$processor.$function"

    /** The context of the [aggregates] the handler names; null when it names none: its module's. */
    val sourceContext: String? get() = aggregates.firstOrNull()?.substringBefore('.')

    /** Whether the handler reads the published streams of a context other than [context], its module's. */
    fun isSourced(context: String): Boolean = sourceContext.let { it != null && it != context }

    /**
     * Where the subscription starts, in a partition it has no checkpoint in yet, when its module's context is
     * [context]: [startFrom] when the handler declares it; otherwise from the beginning for a projection, where the
     * service joins the stream for a reactor's or a saga's handler of another context's stream ([isSourced],
     * [StartFrom.JOIN]), from the end for a reactor or a saga of its own context's log.
     */
    fun startIn(context: String): StartFrom = startFrom ?: when {
        kind == FunctionKind.PROJECT -> StartFrom.BEGINNING
        isSourced(context) -> StartFrom.JOIN
        else -> StartFrom.END
    }

    /**
     * Calls the handler with [event], whose state is a [stateType], and the [context] of its kind ([contextOf]), in
     * the caller's coroutine: running a [blocking] one elsewhere is the runtime's. A saga's requests become the commands its
     * context's [Derivation] makes of them.
     */
    @Suppress("UNCHECKED_CAST") // The subscription reads only the aggregates whose state is a stateType.
    suspend fun invoke(event: StateEvent<*>, context: SubscriptionContext): List<Command<*>> {
        val requests = call(event as StateEvent<S>, context)
        return if (kind == FunctionKind.SAGA) derivationOf(context).commandsOf(requests) else emptyList()
    }

    override fun toString(): String = "ProcessorBinding($name, $kind)"

    companion object {
        private val AGGREGATE = Regex("[^.\\s]+\\.[^.\\s]+")

        /**
         * A `@Projection`'s handler [function] of [processor]; of each event of the [eventType] when it gives one
         * ([eachEvent] calls the function per event).
         */
        @JvmStatic
        @Suppress("LongParameterList")
        fun <S> project(
            processor: String,
            function: String,
            stateType: Class<S & Any>?,
            blocking: Boolean = false,
            startFrom: StartFrom? = null,
            aggregates: List<String> = emptyList(),
            nullableState: Boolean = stateType == null,
            retry: HandlerRetry? = null,
            eventType: Class<*>? = null,
            handle: suspend (StateEvent<S>, SubscriptionContext) -> Unit,
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
                emptyList(),
                eventType = eventType,
            ) { e, c ->
                handle(e, c)
                emptyList()
            }

        /** A `@Reactor`'s handler [function] of [processor]; of each event of the [eventType] when it gives one. */
        @JvmStatic
        @Suppress("LongParameterList")
        fun <S> react(
            processor: String,
            function: String,
            stateType: Class<S & Any>?,
            blocking: Boolean = false,
            startFrom: StartFrom? = null,
            aggregates: List<String> = emptyList(),
            nullableState: Boolean = stateType == null,
            retry: HandlerRetry? = null,
            eventType: Class<*>? = null,
            handle: suspend (StateEvent<S>, SubscriptionContext) -> Unit,
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
                emptyList(),
                eventType = eventType,
            ) { e, c ->
                handle(e, c)
                emptyList()
            }

        /**
         * A `@Saga`'s handler [function] of [processor]: it returns the requests of the commands it derives ([requestsOf]); the saga's
         * `@OnRejected` handlers, the [rejections], take the rejections of those commands, and its `@OnCommitted`
         * handlers, the [commits], their success receipts. Its `SagaContext.call` takes only commands of the classes
         * [calls] lists (`@OnEvent(calls)`).
         */
        @JvmStatic
        @Suppress("LongParameterList")
        fun <S> saga(
            processor: String,
            function: String,
            stateType: Class<S & Any>?,
            blocking: Boolean = false,
            startFrom: StartFrom? = null,
            rejections: List<RejectionBinding<*>> = emptyList(),
            aggregates: List<String> = emptyList(),
            nullableState: Boolean = stateType == null,
            retry: HandlerRetry? = null,
            commits: List<CommittedBinding<*>> = emptyList(),
            calls: List<Class<*>> = emptyList(),
            eventType: Class<*>? = null,
            handle: suspend (StateEvent<S>, SagaContext) -> List<CommandRequest<*>>,
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
                commits,
                calls = calls,
                eventType = eventType,
            ) { e, c ->
                handle(e, c as SagaContext)
            }

        /**
         * A `@Saga`'s `@OnSchedule` handler [function] of [processor], running [job]: a saga handler of
         * the context's job aggregate ([JobState]) that hands each `wow.Ticked` of an instance of [job] to [handle] as
         * a [ScheduleTick], and returns the commands it derives; the ticks of other jobs it skips. [context] is the
         * bounded context its job runs in, as the compiler knew it.
         */
        @JvmStatic
        @Suppress("LongParameterList")
        fun schedule(
            processor: String,
            function: String,
            job: ScheduleJob,
            blocking: Boolean = false,
            rejections: List<RejectionBinding<*>> = emptyList(),
            retry: HandlerRetry? = null,
            commits: List<CommittedBinding<*>> = emptyList(),
            context: String? = null,
            handle: suspend (ScheduleTick, JobContext) -> List<CommandRequest<*>>,
        ): ProcessorBinding<JobState> =
            ProcessorBinding(
                processor,
                function,
                FunctionKind.SAGA,
                JobState::class.java,
                null,
                blocking,
                rejections,
                emptyList(),
                false,
                retry,
                commits,
                job,
                context = context,
            ) { event, subscription ->
                val state = event.state
                if (state.job != job.name) {
                    emptyList()
                } else {
                    event.events.filterIsInstance<Ticked>().flatMap { ticked ->
                        val tick = ScheduleTick(
                            tenantId = event.ref.tenantId,
                            job = job.name,
                            slot = ticked.slot,
                            skipped = ticked.skipped,
                            due = ticked.due,
                            partition = state.partition,
                        )
                        handle(tick, ExecutionJobContext(subscription as SagaContext, tick))
                    }
                }
            }
    }
}

/** Where a subscription without a checkpoint in a partition starts. */
enum class StartFrom {
    /** From the first entry of the partition: a new projection, or a replay under a new name. */
    BEGINNING,

    /** After the entries already committed when the subscription first starts in the partition (its log head). */
    END,

    /**
     * From the first entry when the service joins the stream's topic with it, no other subscription of the service
     * having read it yet; otherwise [END], so a handler added later to a service that reads the stream does not act
     * on its history again: a reactor's or a saga's handler of another context's stream.
     */
    JOIN,
}
