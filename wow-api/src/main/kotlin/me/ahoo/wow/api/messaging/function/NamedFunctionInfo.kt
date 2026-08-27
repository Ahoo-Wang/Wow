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

package me.ahoo.wow.api.messaging.function

import com.fasterxml.jackson.annotation.JsonIgnore
import me.ahoo.wow.api.messaging.processor.ProcessorInfo
import me.ahoo.wow.api.naming.Materialized
import me.ahoo.wow.api.naming.Named

/**
 * Represents named function information with processor context.
 *
 * This interface combines processor identification with function naming,
 * providing complete metadata for locating and invoking specific functions
 * within the messaging system. It extends both [ProcessorInfo] for processor
 * context and [Named] for function identity.
 */
interface NamedFunctionInfo :
    ProcessorInfo,
    Named {
    /**
     * The name of the function.
     *
     * This name uniquely identifies the function within its processor context.
     * Function names should be descriptive and follow naming conventions
     * appropriate for the domain.
     */
    override val name: String

    /**
     * Determines if this function info represents an empty or uninitialized state.
     *
     * An empty function info has blank context name, processor name, and function name.
     * This is useful for detecting placeholder or default function info instances.
     *
     * @return `true` if all name components are empty, `false` otherwise
     */
    @JsonIgnore
    fun isEmpty(): Boolean {
        return contextName.isEmpty() && processorName.isEmpty() && name.isEmpty()
    }
}

/**
 * Immutable data class representing named function information.
 *
 * This class provides a concrete, serializable implementation of [NamedFunctionInfo],
 * containing the essential metadata needed to identify a function within its
 * processor and context. It implements [Materialized] for efficient storage
 * and transmission.
 *
 * @property contextName The name of the bounded context this function belongs to
 * @property processorName The name of the processor that handles this function
 * @property name The unique name of the function within its processor
 */
data class NamedFunctionInfoData(
    override val contextName: String,
    override val processorName: String,
    override val name: String
) : NamedFunctionInfo,
    Materialized {
    companion object {
        /**
         * A constant representing an empty function info.
         *
         * This can be used as a default or placeholder value when function
         * information is not available or needs to be initialized later.
         */
        val EMPTY = NamedFunctionInfoData("", "", "")
    }
}

/**
 * Converts this named function info to a materialized [NamedFunctionInfoData] instance.
 *
 * If this function info is already materialized, returns it directly.
 * Otherwise, creates a new [NamedFunctionInfoData] with the same properties.
 * This is useful for ensuring a concrete, serializable representation
 * of named function information.
 *
 * @receiver The named function info to materialize
 * @return A [NamedFunctionInfoData] instance with the same function metadata
 */
fun NamedFunctionInfo.materialize(): NamedFunctionInfoData {
    if (this is NamedFunctionInfoData) {
        return this
    }
    return NamedFunctionInfoData(
        contextName = contextName,
        processorName = processorName,
        name = name
    )
}
