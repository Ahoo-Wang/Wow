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
import me.ahoo.wow.modeling.toNamedAggregate

interface NamedAggregateGetter<T> {
    fun getNamedAggregate(target: T): NamedAggregate
}

data class MetadataNamedAggregateGetter<T>(val namedAggregate: NamedAggregate) : NamedAggregateGetter<T> {
    override fun getNamedAggregate(target: T): NamedAggregate = namedAggregate
}

class SimpleNamedAggregateGetter<T>(
    private val contextName: String?,
    private val aggregateNameGetter: PropertyGetter<T, String>
) :
    NamedAggregateGetter<T> {

    override fun getNamedAggregate(target: T): NamedAggregate {
        val aggregateName = aggregateNameGetter[target]
        return aggregateName.toNamedAggregate(contextName)
    }
}

fun <T> PropertyGetter<T, String>?.toNamedAggregateGetter(type: Class<T>): NamedAggregateGetter<T>? {
    if (this != null) {
        return SimpleNamedAggregateGetter(type.namedBoundedContext()?.contextName, this)
    }
    return type.namedAggregate()?.let { return MetadataNamedAggregateGetter(it) }
}
