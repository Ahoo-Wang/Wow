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

import java.lang.annotation.Inherited

/**
 * An event handler of a [Projection], [Reactor] or [Saga] (V10, decision D67): it takes a
 * [me.ahoo.wow.api.subscribe.StateEvent] and, optionally, the processor's context. Each handler is its own
 * subscription, named `processor.function`.
 *
 * @param aggregates the aggregates the handler reads, each `context.aggregate`, all of one context (decision D138).
 *   Empty, the default, reads the aggregates of the module's context whose state is the handler's state type. An
 *   aggregate of another context is read from the state events that context publishes (design 04 §5), and the
 *   handler's state type is then the subscriber's view of that state.
 * @param derives the commands a [Saga]'s handler may derive, each by its full type name `context.aggregate.Command`
 *   (decision D138). A wait chain may then follow the saga's hop only into the contexts of these commands. Empty, the
 *   default, leaves it to the handler's return type (`Command<X>`, a list of them), when that names the commands; a
 *   projection or a reactor derives none.
 * @param schedules the commands a [Saga]'s handler may schedule with `SagaContext.schedule` (decision D166), each by
 *   its full type name `context.aggregate.Command`, which the Manifest lists as the subscription's `schedules`. Empty,
 *   the default, declares none; a projection or a reactor schedules none.
 * @param start where the handler's subscription starts in a partition it has no checkpoint in yet (decision D194),
 *   which the compiler writes into the Manifest; the host's `wow.subscriptions.[<processor.function>].start` overrides
 *   it. [Start.DEFAULT] starts a projection, and any handler reading another context's stream, at the
 *   [Start.BEGINNING], so a service that joins later still gets what was published before; a reactor or a saga of its
 *   own context's stream at the [Start.END], so deploying it does not act on the history again. A subscription that
 *   has a checkpoint goes on from it, whatever this says.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class OnEvent(
    val aggregates: Array<String> = [],
    val derives: Array<String> = [],
    val schedules: Array<String> = [],
    val start: Start = Start.DEFAULT,
) {
    /** Where a subscription without a checkpoint starts (decision D194). */
    enum class Start {
        /** As its kind and source say: see [OnEvent.start]. */
        DEFAULT,

        /** From the first entry of the stream. */
        BEGINNING,

        /** After the entries already there when the subscription first reads the stream. */
        END,
    }
}
