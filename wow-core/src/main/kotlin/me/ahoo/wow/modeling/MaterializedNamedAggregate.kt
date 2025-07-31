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
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.naming.getContextAlias
import java.util.*

data class MaterializedNamedAggregate(
    override val contextName: String,
    override val aggregateName: String
) : NamedAggregate, Materialized {

    @Transient
    private val hashCode = Objects.hash(contextName, aggregateName)
    override fun hashCode(): Int = hashCode
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MaterializedNamedAggregate

        if (contextName != other.contextName) return false
        return aggregateName == other.aggregateName
    }
}

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
fun String.toNamedAggregate(contextName: String? = null): MaterializedNamedAggregate {
    val split = split(NAMED_AGGREGATE_DELIMITER)
    if (split.size == 2) {
        return MaterializedNamedAggregate(split[0], split[1])
    }
    require(!contextName.isNullOrEmpty()) {
        "contextName cannot be empty!"
    }
    return MaterializedNamedAggregate(contextName, this)
}

fun NamedBoundedContext.getContextAliasPrefix(): String {
    val alias = getContextAlias()
    if (alias.isBlank()) {
        return alias
    }
    return "$alias$NAMED_AGGREGATE_DELIMITER"
}

fun NamedAggregate.toNamedAggregateString(): String {
    return "$contextName$NAMED_AGGREGATE_DELIMITER$aggregateName"
}

fun NamedAggregate.toStringWithAlias(): String {
    return "${getContextAliasPrefix()}$aggregateName"
}
