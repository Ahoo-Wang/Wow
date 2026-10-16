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
 * A function of an [AggregateRoot] that runs after its command functions (V10, decision D39): it sees the state the
 * command function saw, not yet evolved by its events, and returns events appended after them in the same stream. It
 * does not run when the command is rejected. A function named `afterCommand` needs no annotation.
 *
 * Its parameters are a command function's: first the command, as its body (`Any`, or a command type) or as the
 * admitted `Command<X>`, then dependencies and the `DecideScope`; it may take no command. One taking a command type
 * runs only for the aggregate's commands of that type, and every command it includes must be one.
 *
 * @param include the commands it runs after; empty for every command of the aggregate's command functions. A lifecycle
 *   or schedule command (`DeleteAggregate`, `wow.schedule.Set`) is run after only when included. Each is a command of
 *   the aggregate, or a compile error.
 * @param exclude the commands it does not run after.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class AfterCommand(
    val include: Array<KClass<*>> = [],
    val exclude: Array<KClass<*>> = [],
)
