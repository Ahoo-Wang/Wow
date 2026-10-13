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

package me.ahoo.wow.compiler.model

/**
 * A subscription processor as the model processor read it: a class of one kind (`@Projection`, `@Reactor` or
 * `@Saga`) whose `@OnEvent` functions each become a subscription named `processor.function` (design 04 §1).
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
    /** A saga's `@OnRejected` handlers (decisions D136, D153); the runtime does not bind them yet. */
    val rejections: List<RejectionBinding> = emptyList(),
) {
    /** The generated function that binds the processor's handlers: `cartSagaHandlers` for `CartSaga`. */
    val factoryName: String
        get() = simpleNames.joinToString("").replaceFirstChar { it.lowercase() } + "Handlers"
}

internal data class HandlerBinding(
    val function: String,
    /** The subscription the handler is compiled into: `processor.function`. */
    val subscription: String,
    /** The state type of the `StateEvent` it takes; null for a star projection. */
    val state: String?,
    /** Whether it takes its kind's context after the state event. */
    val context: Boolean,
    val suspending: Boolean,
    /** Whether it returns a Reactor publisher. */
    val reactor: Boolean,
    /** Whether it is annotated `@Blocking`: the runtime calls it on virtual threads (design 01 §4). */
    val blocking: Boolean = false,
    /** `@OnEvent(aggregates)`: the aggregates it reads, each `context.aggregate`, all of one context (decision D138). */
    val aggregates: List<String> = emptyList(),
    /** `@OnEvent(derives)`: the full type names of the commands a saga's handler declares it derives. */
    val derives: List<String> = emptyList(),
    /**
     * The binary names of the command classes a saga's handler returns, as its return type says (`Command<X>`, a
     * list of them); null when it does not say (`Command<*>`), and for a projection or a reactor.
     */
    val derived: List<String>? = null,
) {
    /** The context of the aggregates it reads, when `@OnEvent(aggregates)` names them. */
    val sourceContext: String? get() = aggregates.firstOrNull()?.substringBefore('.')

    /**
     * Whether it reads another context than [context], the module's: the runtime does not subscribe to another
     * context's published streams yet, so such a handler is in the Manifest but gets no binding.
     */
    fun isForeign(context: String?): Boolean = sourceContext != null && sourceContext != context
}

/**
 * An `@OnRejected` handler of a saga (decisions D136, D153): it takes a `CommandRejected<C>` and, optionally, the
 * `SagaContext`, and returns the commands that compensate. Its subscription is named `processor.function` too.
 */
internal data class RejectionBinding(
    val function: String,
    val subscription: String,
    /** The binary name of the command class `C`; null for `CommandRejected<*>`, every command the saga derives. */
    val command: String?,
    val context: Boolean,
    val suspending: Boolean,
    val reactor: Boolean,
    val blocking: Boolean = false,
    /** The binary names of the command classes it returns, as [HandlerBinding.derived]. */
    val derived: List<String>? = null,
)
