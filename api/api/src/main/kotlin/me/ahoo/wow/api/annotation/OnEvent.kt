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
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class OnEvent(
    val aggregates: Array<String> = [],
    val derives: Array<String> = [],
)
