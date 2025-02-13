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
package me.ahoo.wow.command.metadata

import me.ahoo.wow.api.command.DeleteAggregate
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.infra.accessor.property.StaticPropertyGetter
import me.ahoo.wow.metadata.Metadata
import me.ahoo.wow.modeling.matedata.NamedAggregateGetter

/**
 * Command Metadata .
 *
 * @author ahoo wang
 */
data class CommandMetadata<C>(
    val commandType: Class<C>,
    val namedAggregateGetter: NamedAggregateGetter<C>?,
    override val name: String,
    val isCreate: Boolean,
    val allowCreate: Boolean,
    val isVoid: Boolean,
    /**
     * Aggregate ID can be null if it is a create aggregate command.
     */
    val aggregateIdGetter: PropertyGetter<C, String>?,
    val tenantIdGetter: PropertyGetter<C, String>?,
    val ownerIdGetter: PropertyGetter<C, String>?,
    val aggregateVersionGetter: PropertyGetter<C, Int?>?
) : Named, Metadata {

    val isDelete: Boolean
        get() = DeleteAggregate::class.java.isAssignableFrom(commandType)

    val staticTenantId: String?
        get() = if (tenantIdGetter is StaticPropertyGetter) {
            tenantIdGetter.value
        } else {
            null
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommandMetadata<*>) return false

        return commandType == other.commandType
    }

    override fun hashCode(): Int {
        return commandType.hashCode()
    }

    override fun toString(): String {
        return "CommandMetadata(commandType=$commandType)"
    }
}
