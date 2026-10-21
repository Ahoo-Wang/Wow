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
 * @param produces the events of the aggregate the function can decide, which the Manifest lists as the command's
 *   `produces` (decision D120), for a function whose return type does not say: a `Decision`, a `Mono<Decision>`,
 *   `Any`. Each must be an event of the aggregate. A function whose return type already names its events (an event,
 *   a sealed type, a list of them) may not declare them again. Empty, the default, declares nothing: `produces` stays
 *   unknown.
 * @param schedules the commands the function may schedule with `DecideScope.schedule` (decision D166), each by its
 *   full type name `context.aggregate.Command`, which the Manifest lists as the aggregate's `schedules`. The compiler
 *   cannot read the calls in a function's body, so they are declared; empty, the default, declares none.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class OnCommand(
    val produces: Array<KClass<*>> = [],
    val schedules: Array<String> = [],
)
