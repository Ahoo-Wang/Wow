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

/**
 * Interface for entities that may have associated function information.
 *
 * This interface provides optional access to function metadata, allowing
 * entities to be linked to specific functions in the messaging system.
 * The nullable nature indicates that the association may not always be present.
 *
 * @param FUN The type of function info, constrained to [NamedFunctionInfo]
 */
interface NullableFunctionInfoCapable<FUN : NamedFunctionInfo> {
    /**
     * The associated function information, or null if not available.
     *
     * This property provides access to the function metadata when it exists,
     * enabling function-aware processing and routing decisions.
     */
    val function: FUN?
}

/**
 * Interface for entities that must have associated function information.
 *
 * This interface extends [NullableFunctionInfoCapable] to guarantee that
 * function information is always available. Entities implementing this
 * interface are guaranteed to have valid function metadata for processing.
 *
 * @param FUN The type of function info, constrained to [NamedFunctionInfo]
 */
interface FunctionInfoCapable<FUN : NamedFunctionInfo> : NullableFunctionInfoCapable<FUN> {
    /**
     * The associated function information, guaranteed to be non-null.
     *
     * This property provides mandatory access to function metadata,
     * ensuring that implementing entities always have valid function context.
     */
    override val function: FUN
}
