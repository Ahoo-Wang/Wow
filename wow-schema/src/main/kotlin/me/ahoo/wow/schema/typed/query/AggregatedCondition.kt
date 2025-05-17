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

package me.ahoo.wow.schema.typed.query

import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.schema.typed.AggregatedFields

interface IAggregatedCondition<CommandAggregateType : Any> {
    val field: AggregatedFields<CommandAggregateType>

    @get:Schema(defaultValue = "ALL")
    val operator: Operator
    val value: Any

    @get:Schema(defaultValue = "{}")
    val options: Map<String, Any>
}

data class AggregatedCondition<CommandAggregateType : Any>(
    override val field: AggregatedFields<CommandAggregateType>,
    override val operator: Operator,
    override val value: Any,
    @get:Schema(defaultValue = "[]")
    val children: List<AggregatedCondition<CommandAggregateType>>,
    override val options: Map<String, Any> = emptyMap()
) : IAggregatedCondition<CommandAggregateType>
