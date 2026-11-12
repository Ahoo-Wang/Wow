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
 * The name of an aggregate, a processor, a command or an event, in place of the one derived
 * from the class's simple name. Commands and events are typed `context.aggregate.Name`.
 *
 * On a subscription handler (an [OnEvent], [OnRejected], [OnCommitted] or [OnSchedule] function), it is the handler's
 * name in its subscription, `processor.name`, in place of the function's: the subscription, its checkpoint and its
 * executions keep that name, so renaming the function with `@Name("<its former name>")` keeps the subscription.
 *
 * A parameter's dependency is named by [DependencyName], not by this.
 *
 * @param value the name.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class Name(
    val value: String,
)
