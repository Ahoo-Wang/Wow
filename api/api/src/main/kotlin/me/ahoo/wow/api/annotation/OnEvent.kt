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

package me.ahoo.wow.api.annotation

/**
 * An event handler of a [Projection], [Reactor] or [Saga]: it takes a
 * [me.ahoo.wow.api.subscribe.StateEvent] and, optionally, the processor's context: a projection's or a reactor's
 * `SubscriptionContext`, a saga's `SagaContext` or the `SubscriptionContext` it extends. A saga's handler declares
 * what it sends with [Sends]. Each handler is its own
 * subscription, named `processor.function`, or `processor.name` with its [Name]. Only this annotation declares a
 * handler, whatever the function's name.
 *
 * @param aggregates the aggregates the handler reads, each `context.aggregate`, all of one context.
 *   Empty, the default, reads the aggregates of the module's context whose state is the handler's state type. An
 *   aggregate of another context is read from the state events that context publishes, and the
 *   handler's state type is then the subscriber's view of that state.
 * @param start where the handler's subscription starts in a partition it has no checkpoint in yet,
 *   which the compiler writes into the Manifest; the host's
 *   `wow.subscriptions.[<context>.<processor.function>].start` overrides it. [Start.DEFAULT] starts a projection at
 *   the [Start.BEGINNING]; a reactor or a saga of its own context's stream at the [Start.END], so deploying it does
 *   not act on the history again; a reactor's or a saga's handler of another
 *   context's stream where its service joins the stream: at the [Start.BEGINNING] when no other subscription of the
 *   service reads it yet, so a service that joins later still gets what was published before, else at the
 *   [Start.END], so a handler added later does not act on it again. A subscription that has a checkpoint goes on
 *   from it, whatever this says.
 */
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnEvent(
    val aggregates: Array<String> = [],
    val start: Start = Start.DEFAULT,
) {
    /** Where a subscription without a checkpoint starts. */
    enum class Start {
        /** As its kind and source say: see [OnEvent.start]. */
        DEFAULT,

        /** From the first entry of the stream. */
        BEGINNING,

        /** After the entries already there when the subscription first reads the stream. */
        END,
    }
}
