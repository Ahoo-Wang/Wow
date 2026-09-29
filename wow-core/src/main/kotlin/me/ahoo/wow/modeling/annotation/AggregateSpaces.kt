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

package me.ahoo.wow.modeling.annotation

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.aggregateType

/**
 * Whether commands to this aggregate may carry a space: its [spaced][me.ahoo.wow.modeling.metadata.AggregateMetadata.spaced]
 * when this service knows the aggregate's type, and `true` when it does not (the command is for another service,
 * whose command aggregate ignores the space on arrival if the aggregate is not spaced).
 */
internal fun NamedAggregate.acceptsCommandSpace(): Boolean =
    aggregateType<Any>()?.aggregateMetadata<Any, Any>()?.spaced ?: true
