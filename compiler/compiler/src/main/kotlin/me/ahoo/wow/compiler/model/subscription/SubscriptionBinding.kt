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

package me.ahoo.wow.compiler.model.subscription

import me.ahoo.wow.compiler.model.aggregate.Dependency

/**
 * A subscription processor as the model processor read it: a class of one kind (`@Projection`, `@Reactor` or
 * `@Saga`) whose `@OnEvent` functions each become a subscription named `processor.function`.
 */
internal data class SubscriptionBinding(
    /** The processor's qualified class name. */
    val processor: String,
    /** The processor's name: its `@Name`, or its class's simple name. */
    val name: String,
    /** `PROJECT`, `REACT` or `SAGA`; null when the class declares no kind or more than one. */
    val kind: String?,
    val handlers: List<HandlerBinding>,
    val problems: List<String>,
    /** The processor's package, where its generated handlers go. */
    val packageName: String = "",
    /** The simple names of the processor's class and of the classes it is nested in, outermost first. */
    val simpleNames: List<String> = emptyList(),
    /** Whether the processor is an `object`: its handlers use the instance instead of constructing one. */
    val isObject: Boolean = false,
    /** The processor's constructor parameters, in order: domain services from the host's `Dependencies`. */
    val dependencies: List<Dependency> = emptyList(),
    /** A saga's `@OnRejected` handlers: bound with each of its handlers. */
    val rejections: List<ReceiptBinding> = emptyList(),
    /** A saga's `@OnCommitted` handlers: bound with each of its handlers. */
    val commits: List<ReceiptBinding> = emptyList(),
    /**
     * Whether the processor or its constructor is `internal`, so the generated function that takes its services is
     * too.
     */
    val internal: Boolean = false,
) {
    /** The generated function that binds the processor's handlers: `cartSagaHandlers` for `CartSaga`. */
    val factoryName: String
        get() = simpleNames.joinToString("").replaceFirstChar { it.lowercase() } + "Handlers"
}

internal data class HandlerBinding(
    /** The handler's name in its subscription: its `@Name`, else its function's name. */
    val function: String,
    /** The subscription the handler is compiled into: `processor.function`. */
    val subscription: String,
    /** The function the generated binding calls; [function] unless the handler is `@Name`d otherwise. */
    val callee: String = function,
    /** The state type of the `StateEvent` it takes; null for a star projection. */
    val state: String?,
    /**
     * Whether it takes a null state: `StateEvent<S?>`, or `StateEvent<*>`, whose state may be anything.
     */
    val nullableState: Boolean = state == null,
    /** Whether it takes its kind's context after the state event. */
    val context: Boolean,
    val suspending: Boolean,
    /** Whether it returns a Reactor publisher. */
    val reactor: Boolean,
    /** Whether it is annotated `@Blocking`: the runtime calls it on virtual threads. */
    val blocking: Boolean = false,
    /** `@OnEvent(aggregates)`: the aggregates it reads, each `context.aggregate`, all of one context. */
    val aggregates: List<String> = emptyList(),
    /**
     * `@OnEvent(derives)`: the binary names of the command classes a saga's handler declares it derives, a sealed type
     * read as its concrete subclasses.
     */
    val derives: List<String> = emptyList(),
    /** `@OnEvent(schedules)`: the binary names of the command classes a saga's handler declares it schedules. */
    val schedules: List<String> = emptyList(),
    /**
     * `@OnEvent(calls)`: the binary names of the command classes a saga's handler declares it calls with
     * `SagaContext.call`, a sealed type read as its concrete subclasses.
     */
    val calls: List<String> = emptyList(),
    /** The qualified names of [calls], as the generated binding writes them. */
    val callSources: List<String> = emptyList(),
    /**
     * `@OnEvent(start)`: `BEGINNING` or `END` when the handler declares where its subscription starts;
     * null leaves it to its kind and source ([startIn]).
     */
    val start: String? = null,
    /**
     * The binary names of the command classes a saga's handler returns, as its return type says (`Command<X>`, a
     * list of them, the subclasses of a sealed `X`; none for `Unit`); null when it does not say (`Command<*>`), and for
     * a projection or a reactor.
     */
    val derived: List<String>? = null,
    /**
     * Its in-process retry budget: its own `@Retry`, each setting it does not give its processor's; null
     * when neither gives any.
     */
    val retry: RetryBinding? = null,
    /** The job of a saga's `@OnSchedule` handler; null for an `@OnEvent` handler. */
    val job: JobBinding? = null,
    /** The event a handler of each event of a type takes; null for a handler of the whole stream. */
    val event: EventParameter? = null,
) {
    /** The context of the aggregates it reads, when `@OnEvent(aggregates)` names them. */
    val sourceContext: String? get() = aggregates.firstOrNull()?.substringBefore('.')

    /** Whether the handler reads another context's events: an aggregate of it, or its events by type. */
    fun readsPeer(context: String): Boolean =
        (sourceContext != null && sourceContext != context) || event?.peerEvents.orEmpty().isNotEmpty()

    /**
     * Where its subscription starts, `BEGINNING` or `END`, in a module of [context]: its declared
     * [start], else the beginning for a projection, the end for a reactor or a saga of its own context's log; null for
     * a reactor's or a saga's handler of another context's stream, which starts where its service joins the stream
     * (the runtime's `StartFrom.JOIN`). [kind] is its processor's, `PROJECT`, `REACT` or `SAGA`.
     */
    fun startIn(context: String, kind: String): String? = start ?: when {
        kind == "PROJECT" -> "BEGINNING"
        readsPeer(context) -> null
        else -> "END"
    }
}

/**
 * The two kinds of a saga's receipt handlers: `@OnRejected`, taking a `CommandRejected<C>`, and
 * `@OnCommitted`, taking a `CommandCommitted<C>`; [binding] is the kernel's class of its binding.
 */
internal enum class Receipt(
    val annotation: String,
    val parameter: String,
    val binding: String,
    /** What the handler takes, in the singular and the plural. */
    val noun: String,
    val nouns: String,
) {
    REJECTED(
        "me.ahoo.wow.api.annotation.OnRejected",
        "me.ahoo.wow.api.subscribe.CommandRejected",
        "me.ahoo.wow.kernel.RejectionBinding",
        "rejection",
        "rejections",
    ),
    COMMITTED(
        "me.ahoo.wow.api.annotation.OnCommitted",
        "me.ahoo.wow.api.subscribe.CommandCommitted",
        "me.ahoo.wow.kernel.CommittedBinding",
        "receipt",
        "receipts",
    ),
    ;

    /** `@OnRejected` or `@OnCommitted`. */
    val label: String get() = "@" + annotation.substringAfterLast('.')

    /** `CommandRejected` or `CommandCommitted`. */
    val parameterName: String get() = parameter.substringAfterLast('.')
}

/**
 * A receipt handler of a saga, of the [receipt] kind: an `@OnRejected` handler, which takes a
 * `CommandRejected<C>`, or an `@OnCommitted` one, which takes a `CommandCommitted<C>`, and optionally
 * the `SagaContext`; it returns the commands it derives. Its subscription is named `processor.function` too.
 */
internal data class ReceiptBinding(
    /** The handler's name in its subscription: its `@Name`, else its function's name. */
    val function: String,
    val subscription: String,
    /** The function the generated binding calls; [function] unless the handler is `@Name`d otherwise. */
    val callee: String = function,
    /** The binary name of the command class `C`; null for `CommandRejected<*>`, every command the saga derives. */
    val command: String?,
    val context: Boolean,
    val suspending: Boolean,
    val reactor: Boolean,
    val blocking: Boolean = false,
    /** The binary names of the command classes it returns, as [HandlerBinding.derived]. */
    val derived: List<String>? = null,
    /** The qualified name of `C`, as the generated binding writes it; null for `CommandRejected<*>`. */
    val commandSource: String? = null,
    val receipt: Receipt = Receipt.REJECTED,
)
