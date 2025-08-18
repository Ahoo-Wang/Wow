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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.metadata.Metadata
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata

/**
 * StateAggregateMetadata .
 *
 * @author ahoo wang
 */
data class AggregateMetadata<C : Any, S : Any>(
    override val namedAggregate: NamedAggregate,
    val staticTenantId: String?,
    val state: StateAggregateMetadata<S>,
    val command: CommandAggregateMetadata<C>
) : NamedAggregateDecorator, Metadata {

    /**
     * Are state aggregation and command aggregation a aggregation relationship?.
     */
    val isAggregationPattern: Boolean
        get() = command.aggregateType != state.aggregateType

    private fun extractAggregateId(state: S, aggregateId: String): String {
        val accessor = this.state.aggregateIdAccessor
        if (accessor != null) {
            return accessor[state]
        }
        require(aggregateId.isNotBlank()) {
            "aggregateIdAccessor and aggregateId cannot both be null or blank."
        }
        return aggregateId
    }

    fun extractAggregateId(state: S, aggregateId: String, tenantId: String = TenantId.DEFAULT_TENANT_ID): AggregateId {
        val aggregateIdStr = extractAggregateId(state, aggregateId)
        return aggregateId(id = aggregateIdStr, tenantId = tenantId)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AggregateMetadata<*, *>) return false

        return command == other.command
    }

    override fun hashCode(): Int {
        return command.hashCode()
    }

    override fun toString(): String {
        return "AggregateMetadata(command=$command)"
    }
}

fun <C : Any, S : Any> NamedAggregate.asAggregateMetadata(): AggregateMetadata<C, S> {
    if (this is AggregateMetadata<*, *>) {
        @Suppress("UNCHECKED_CAST")
        return this as AggregateMetadata<C, S>
    }
    return requiredAggregateType<C>().aggregateMetadata()
}
