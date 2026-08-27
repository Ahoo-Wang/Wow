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

/**
 * Represents a materialized named aggregate, providing concrete implementations of context and aggregate names.
 *
 * This data class implements both [NamedAggregate] and [Materialized], offering a fully resolved
 * representation of an aggregate within a bounded context. It is used when the aggregate names
 * are known at runtime and need to be stored or compared efficiently.
 *
 * @property contextName The name of the bounded context this aggregate belongs to.
 * @property aggregateName The name of the aggregate within the bounded context.
 *
 * @constructor Creates a new MaterializedNamedAggregate with the specified context and aggregate names.
 */
data class MaterializedNamedAggregate(
    override val contextName: String,
    override val aggregateName: String
) : NamedAggregate,
    Materialized {
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

/**
 * Materializes this [NamedAggregate] into a [MaterializedNamedAggregate].
 *
 * This extension function converts any NamedAggregate implementation into its materialized form.
 * If the aggregate is already materialized, it returns itself. If it's a decorator, it recursively
 * materializes the underlying aggregate. Otherwise, it creates a new MaterializedNamedAggregate
 * with the context and aggregate names.
 *
 * @return A [MaterializedNamedAggregate] representing this named aggregate.
 *
 * @see MaterializedNamedAggregate
 * @see NamedAggregateDecorator
 */
fun NamedAggregate.materialize(): MaterializedNamedAggregate {
    if (this is MaterializedNamedAggregate) {
        return this
    }
    if (this is NamedAggregateDecorator) {
        return namedAggregate.materialize()
    }
    return MaterializedNamedAggregate(contextName, aggregateName)
}

/**
 * The delimiter used to separate context name and aggregate name in string representations.
 */
const val NAMED_AGGREGATE_DELIMITER = "."

/**
 * Converts this string into a [MaterializedNamedAggregate].
 *
 * This extension function parses a string representation of a named aggregate. If the string contains
 * the delimiter, it splits into context and aggregate names. Otherwise, it uses the provided contextName
 * parameter to create the aggregate.
 *
 * @param contextName The context name to use if the string doesn't contain the delimiter. Must not be null or empty if the string is a single aggregate name.
 * @return A [MaterializedNamedAggregate] parsed from this string.
 * @throws IllegalArgumentException if contextName is null or empty when required.
 *
 * @see NAMED_AGGREGATE_DELIMITER
 * @see MaterializedNamedAggregate
 */
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

/**
 * Gets the context alias prefix for this bounded context.
 *
 * This extension function retrieves the alias for the bounded context and appends the delimiter
 * if the alias is not blank. This is useful for creating prefixed aggregate names.
 *
 * @return The context alias with delimiter if alias exists, otherwise an empty string.
 *
 * @see NamedBoundedContext.getContextAlias
 * @see NAMED_AGGREGATE_DELIMITER
 */
fun NamedBoundedContext.getContextAliasPrefix(): String {
    val alias = getContextAlias()
    if (alias.isBlank()) {
        return alias
    }
    return "$alias$NAMED_AGGREGATE_DELIMITER"
}

/**
 * Converts this [NamedAggregate] to its string representation.
 *
 * This extension function creates a string in the format "contextName.aggregateName" using the delimiter.
 *
 * @return A string representation of this named aggregate.
 *
 * @see NAMED_AGGREGATE_DELIMITER
 */
fun NamedAggregate.toNamedAggregateString(): String = "$contextName$NAMED_AGGREGATE_DELIMITER$aggregateName"

/**
 * Converts this [NamedAggregate] to a string representation using the context alias.
 *
 * This extension function creates a string using the context alias prefix followed by the aggregate name.
 * This is useful for display purposes where the full context name might be too verbose.
 *
 * @return A string representation using the context alias.
 *
 * @see NamedBoundedContext.getContextAliasPrefix
 */
fun NamedAggregate.toStringWithAlias(): String = "${getContextAliasPrefix()}$aggregateName"
