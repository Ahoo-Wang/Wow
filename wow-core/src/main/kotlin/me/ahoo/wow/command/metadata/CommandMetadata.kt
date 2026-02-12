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
 * Metadata container for command classes.
 *
 * CommandMetadata holds all the reflective information about a command class,
 * including property getters for aggregate targeting, command characteristics,
 * and naming information. This metadata is used by the command processing
 * pipeline to properly route and handle commands.
 *
 * @param C the type of the command class
 * @property commandType the Class object representing the command type
 * @property namedAggregateGetter getter for the named aggregate information
 * @property name the command name (typically the class name)
 * @property isCreate whether this command creates new aggregates
 * @property allowCreate whether this command allows creating aggregates if they don't exist
 * @property isVoid whether this command returns no result (fire-and-forget)
 * @property aggregateIdGetter property getter for the aggregate ID (can be null for create commands)
 * @property tenantIdGetter property getter for the tenant ID
 * @property ownerIdGetter property getter for the owner ID
 * @property aggregateVersionGetter property getter for the expected aggregate version
 * @author ahoo wang
 * @see Named
 * @see Metadata
 * @see PropertyGetter
 */
data class CommandMetadata<C>(
    val commandType: Class<C>,
    val namedAggregateGetter: NamedAggregateGetter<C>?,
    override val name: String,
    val isCreate: Boolean,
    val allowCreate: Boolean,
    val isVoid: Boolean,
    val aggregateIdGetter: PropertyGetter<C, String>?,
    val tenantIdGetter: PropertyGetter<C, String>?,
    val ownerIdGetter: PropertyGetter<C, String>?,
    val aggregateVersionGetter: PropertyGetter<C, Int?>?
) : Named,
    Metadata {
    /**
     * Whether this command deletes aggregates.
     *
     * Determined by checking if the command type implements DeleteAggregate.
     */
    val isDelete: Boolean
        get() = DeleteAggregate::class.java.isAssignableFrom(commandType)

    /**
     * The static tenant ID if the tenant ID getter is a static property getter.
     *
     * Returns null if the tenant ID is dynamic or not set.
     */
    val staticTenantId: String?
        get() =
            if (tenantIdGetter is StaticPropertyGetter) {
                tenantIdGetter.value
            } else {
                null
            }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommandMetadata<*>) return false

        return commandType == other.commandType
    }

    override fun hashCode(): Int = commandType.hashCode()

    override fun toString(): String = "CommandMetadata(commandType=$commandType)"
}
