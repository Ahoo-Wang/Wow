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

package me.ahoo.wow.modeling.matedata

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.namedAggregate
import me.ahoo.wow.configuration.namedBoundedContext
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate

/**
 * Interface for retrieving a [NamedAggregate] from a target object.
 *
 * Implementations of this interface provide a way to extract or derive named aggregate information
 * from various types of objects, such as state aggregates or metadata.
 *
 * @param T The type of the target object.
 */
interface NamedAggregateGetter<T> {
    /**
     * Retrieves the named aggregate from the target object.
     *
     * @param target The target object to extract the named aggregate from.
     * @return The named aggregate associated with the target.
     */
    fun getNamedAggregate(target: T): NamedAggregate
}

/**
 * A named aggregate getter that returns the target itself if it's already a [NamedAggregate].
 *
 * This implementation is used when the target object is already a named aggregate and just needs to be materialized.
 */
object SelfNamedAggregateGetter : NamedAggregateGetter<NamedAggregate> {
    /**
     * Returns the materialized version of the target named aggregate.
     *
     * @param target The named aggregate to materialize.
     * @return The materialized named aggregate.
     */
    override fun getNamedAggregate(target: NamedAggregate): NamedAggregate = target.materialize() as NamedAggregate
}

/**
 * A named aggregate getter that always returns a predefined named aggregate from metadata.
 *
 * This implementation is used when the named aggregate is known from configuration or metadata
 * and doesn't need to be derived from the target object.
 *
 * @param T The type of the target object.
 * @property namedAggregate The predefined named aggregate to return.
 */
data class MetadataNamedAggregateGetter<T>(
    val namedAggregate: NamedAggregate
) : NamedAggregateGetter<T> {
    /**
     * Returns the predefined named aggregate, ignoring the target.
     *
     * @param target The target object (ignored).
     * @return The predefined named aggregate.
     */
    override fun getNamedAggregate(target: T): NamedAggregate = namedAggregate
}

/**
 * A named aggregate getter that extracts the aggregate name from a property of the target object.
 *
 * This implementation uses a property getter to extract the aggregate name from the target,
 * then constructs a named aggregate using an optional context name.
 *
 * @param T The type of the target object.
 * @property contextName The optional context name to use when constructing the named aggregate.
 * @property aggregateNameGetter The property getter for extracting the aggregate name from the target.
 */
class SimpleNamedAggregateGetter<T>(
    private val contextName: String?,
    private val aggregateNameGetter: PropertyGetter<T, String>
) : NamedAggregateGetter<T> {
    /**
     * Retrieves the named aggregate by extracting the aggregate name from the target and constructing a named aggregate.
     *
     * @param target The target object to extract the aggregate name from.
     * @return The constructed named aggregate.
     */
    override fun getNamedAggregate(target: T): NamedAggregate {
        val aggregateName = aggregateNameGetter[target]
        return aggregateName.toNamedAggregate(contextName)
    }
}

/**
 * Converts a property getter into a named aggregate getter.
 *
 * This extension function creates an appropriate named aggregate getter based on the availability
 * of a property getter and type metadata. If a property getter is provided, it creates a
 * [SimpleNamedAggregateGetter]. Otherwise, it attempts to create a [MetadataNamedAggregateGetter]
 * from the type's named aggregate metadata.
 *
 * @param T The type of the target object.
 * @param type The class of the target type.
 * @return A named aggregate getter, or null if neither property getter nor metadata is available.
 */
fun <T> PropertyGetter<T, String>?.toNamedAggregateGetter(type: Class<T>): NamedAggregateGetter<T>? {
    if (this != null) {
        return SimpleNamedAggregateGetter(type.namedBoundedContext()?.contextName, this)
    }
    return type.namedAggregate()?.let { return MetadataNamedAggregateGetter(it) }
}
