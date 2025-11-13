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

import me.ahoo.wow.api.naming.Materialized

/**
 * Immutable data class representing complete function information.
 *
 * This class provides a concrete, serializable implementation of [FunctionInfo],
 * containing all the metadata needed to identify and route to a specific function
 * in the messaging system. It implements [Materialized] for efficient storage
 * and transmission.
 *
 * @property functionKind The kind of function (command, event, etc.)
 * @property contextName The name of the bounded context this function belongs to
 * @property processorName The name of the processor that handles this function
 * @property name The unique name of the function within its processor
 */
data class FunctionInfoData(
    override val functionKind: FunctionKind,
    override val contextName: String,
    override val processorName: String,
    override val name: String
) : FunctionInfo,
    Materialized {
    companion object {
        private const val UNKNOWN = "Unknown"

        /**
         * Creates a function info for an unknown or unspecified function.
         *
         * This factory method is useful for creating placeholder function info
         * when the actual function details are not available or need to be
         * determined later. Unknown components default to "Unknown".
         *
         * @param functionKind The function kind (required)
         * @param contextName The bounded context name (required)
         * @param processorName The processor name (defaults to "Unknown")
         * @param functionName The function name (defaults to "Unknown")
         * @return A new [FunctionInfoData] instance with the specified or default values
         *
         * @sample
         * ```kotlin
         * val unknownCommand = FunctionInfoData.unknown(FunctionKind.COMMAND, "OrderContext")
         * ```
         */
        fun unknown(
            functionKind: FunctionKind,
            contextName: String,
            processorName: String = UNKNOWN,
            functionName: String = UNKNOWN
        ): FunctionInfoData = FunctionInfoData(functionKind, contextName, processorName, functionName)
    }
}

/**
 * Converts this function info to a materialized [FunctionInfoData] instance.
 *
 * If this function info is already materialized, returns it directly.
 * Otherwise, creates a new [FunctionInfoData] with the same properties.
 * This is useful for ensuring a concrete, serializable representation
 * of function information.
 *
 * @receiver The function info to materialize
 * @return A [FunctionInfoData] instance with the same function metadata
 */
fun FunctionInfo.materialize(): FunctionInfoData {
    if (this is Materialized) {
        return this as FunctionInfoData
    }
    return FunctionInfoData(
        contextName = contextName,
        processorName = processorName,
        name = name,
        functionKind = functionKind,
    )
}
