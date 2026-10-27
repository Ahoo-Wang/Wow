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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateType

/**
 * A command's handling produced an event its function does not declare: a decide function or an after hook of the
 * model of [type] produced [event] for [command], outside the events its return type yields or its `produces` lists, or
 * a lifecycle command's decide function another lifecycle event than its own. A bug in the model, not an outcome of the
 * command: the command fails (`wow.command.undeclared-event`) and commits nothing.
 */
class UndeclaredEventException(
    val type: AggregateType,
    val command: Class<*>,
    val event: Class<*>,
    message: String,
) : RuntimeException(message)
