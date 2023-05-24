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

package me.ahoo.wow.modeling

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.naming.Materialized

data class MaterializedNamedAggregate(
    override val contextName: String,
    override val aggregateName: String
) : NamedAggregate, Materialized

fun NamedAggregate.materialize(): MaterializedNamedAggregate {
    if (this is MaterializedNamedAggregate) {
        return this
    }
    if (this is NamedAggregateDecorator) {
        return namedAggregate.materialize()
    }
    return MaterializedNamedAggregate(contextName, aggregateName)
}

const val NAMED_AGGREGATE_DELIMITER = "."
fun String.asNamedAggregate(contextName: String? = null): MaterializedNamedAggregate {
    val split = split(NAMED_AGGREGATE_DELIMITER)
    if (split.size == 2) {
        return MaterializedNamedAggregate(split[0], split[1])
    }
    require(!contextName.isNullOrEmpty()) {
        "contextName cannot be empty!"
    }
    return MaterializedNamedAggregate(contextName, this)
}

fun NamedAggregate.asNamedAggregateString(): String {
    return "$contextName$NAMED_AGGREGATE_DELIMITER$aggregateName"
}
