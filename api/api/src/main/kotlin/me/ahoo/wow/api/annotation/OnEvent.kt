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
import kotlin.reflect.KClass

/**
 * An event handler of a [Projection], [Reactor] or [Saga]: it takes a
 * [me.ahoo.wow.api.subscribe.StateEvent] and, optionally, the processor's context. Each handler is its own
 * subscription, named `processor.function`, or `processor.name` with its [Name]. Only this annotation declares a
 * handler, whatever the function's name.
 *
 * @param aggregates the aggregates the handler reads, each `context.aggregate`, all of one context.
 *   Empty, the default, reads the aggregates of the module's context whose state is the handler's state type. An
 *   aggregate of another context is read from the state events that context publishes, and the
 *   handler's state type is then the subscriber's view of that state.
 * @param derives the commands a [Saga]'s handler may derive, by their classes: a command class of
 *   this context or of another (from that context's api), or a sealed type, which stands for its concrete subclasses.
 *   A wait chain may then follow the saga's hop only into the contexts of these commands, and the service's build
 *   fails on a class that neither this context nor a peer contract on its class path decides. Empty, the default,
 *   leaves it to the handler's return type (`CommandRequest<X>`, an `Iterable`, a `Sequence` or a `Flow` of them, the
 *   subclasses of a sealed `X`), when that names the commands; the compiler warns about a handler whose return type
 *   names none. A handler that returns `Unit` derives none and declares none. A projection or a reactor derives none.
 * @param schedules the commands a [Saga]'s handler may schedule with `SagaContext.schedule`, by their classes,
 *   as [derives] names them, which the Manifest lists as the subscription's `schedules`. Empty, the default, declares
 *   none; a projection or a reactor schedules none.
 * @param calls the commands a [Saga]'s handler sends to another context with `SagaContext.call`,
 *   by their classes, as [derives] names them: the compiler lists them as the subscription's `calls` in the Manifest,
 *   which a wait chain follows as it follows [derives], the node does not build when a called context is its own, is
 *   unknown, or is one its command caller says it does not reach (`wow.peers.<context>.url`), and `call` refuses a command of a class the handler does not
 *   declare. A called command needs no command topic, and is not one the handler derives. A command of the handler's
 *   own context is derived, never called. A projection or a reactor calls none.
 * @param start where the handler's subscription starts in a partition it has no checkpoint in yet,
 *   which the compiler writes into the Manifest; the host's `wow.subscriptions.[<processor.function>].start` overrides
 *   it. [Start.DEFAULT] starts a projection at the [Start.BEGINNING]; a reactor or a saga of its own context's stream
 *   at the [Start.END], so deploying it does not act on the history again; a reactor's or a saga's handler of another
 *   context's stream where its service joins the stream: at the [Start.BEGINNING] when no other subscription of the
 *   service reads it yet, so a service that joins later still gets what was published before, else at the
 *   [Start.END], so a handler added later does not act on it again. A subscription that has a checkpoint goes on
 *   from it, whatever this says.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class OnEvent(
    val aggregates: Array<String> = [],
    val derives: Array<KClass<*>> = [],
    val schedules: Array<KClass<*>> = [],
    val calls: Array<KClass<*>> = [],
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
