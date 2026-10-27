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
 * A command function of an [AggregateRoot]: it takes the command, or a `Command` of it, and decides the events.
 * A function named `onCommand` needs no annotation, but only the annotation declares [produces].
 *
 * Its return type says what it decides, in this order of preference:
 * - the event it produces (`CartClosed`), or `E?` when the command may change nothing (null is a no-op), a sealed type
 *   of its events, or a list of one event type; it rejects by throwing: `DecideScope.reject(code, message)`,
 *   `require` (`wow.command.invalid`) or `check` (`wow.command.refused`). The compiler reads the events from the type,
 *   and the kernel fails a command whose function produces another (`wow.command.undeclared-event`).
 * - `Decision`, for a return type that cannot name its events (two kinds of event without a common sealed type, a
 *   domain event and a lifecycle event), with [produces] listing them; the kernel holds the function to that list.
 *   Without [produces], the Manifest lists no events for it, and the kernel does not check them.
 *
 * Java has no `DecideScope` receiver: a Java function returns its event, or `null`, and rejects by throwing
 * `RejectedException`, or returns a `Decision`.
 *
 * @param produces the events of the aggregate the function can decide, which the Manifest lists as the command's
 *   `produces`, for a function whose return type does not say: a `Decision`, a `Mono<Decision>`,
 *   `Any`. Each must be an event of the aggregate. A function whose return type already names its events (an event,
 *   a sealed type, a list of them) may not declare them again. Empty, the default, declares nothing: `produces` stays
 *   unknown.
 * @param schedules the commands the function may schedule with `DecideScope.schedule`, by their classes: a command
 *   class of this context or of another (from that context's api), or a sealed type, which stands for its concrete
 *   subclasses. The Manifest lists them as the aggregate's `schedules`, by full type name (a schema reference for
 *   another context's). The compiler cannot read the calls in a function's body, so they are declared; empty, the
 *   default, declares none.
 * @param onDeleted whether the command is decided on a deleted aggregate too, as `RestoreAggregate` is: a command that
 *   restores it, producing `AggregateRestored` beside its own events, or one a deleted aggregate still takes. Every
 *   other command is refused on a deleted aggregate. Such a command produces `AggregateRestored` and its aggregate's
 *   own events alone. False by default.
 *
 * A command changes the aggregate's lifecycle by producing the lifecycle event beside its own events, in one commit:
 * `CloseCart(reason)` produces `CartClosed(reason)` and `AggregateDeleted`; the kernel then marks it deleted, as
 * `DeleteAggregate` does. Deciding the same event twice changes nothing more; a command that would change nothing
 * returns `Decision.NoOp`.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class OnCommand(
    val produces: Array<KClass<*>> = [],
    val schedules: Array<KClass<*>> = [],
    val onDeleted: Boolean = false,
)
