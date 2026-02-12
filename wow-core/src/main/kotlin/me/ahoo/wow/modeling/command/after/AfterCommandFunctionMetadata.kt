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

package me.ahoo.wow.modeling.command.after

import me.ahoo.wow.api.Ordered
import me.ahoo.wow.api.annotation.AfterCommand
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.messaging.function.FunctionAccessorMetadata
import me.ahoo.wow.messaging.function.toMessageFunction
import reactor.core.publisher.Mono

/**
 * Metadata for an after-command function, containing configuration about which commands it applies to.
 *
 * This data class holds information about an after-command function's include/exclude rules and ordering,
 * parsed from the [AfterCommand] annotation on the function.
 *
 * @param C The type of the command aggregate.
 * @property function The underlying function accessor metadata.
 * @property include Set of command classes that this function should execute for (empty means all).
 * @property exclude Set of command classes that this function should not execute for.
 * @property order The ordering information for this function.
 *
 * @constructor Creates metadata by parsing annotations from the function.
 */
data class AfterCommandFunctionMetadata<C : Any>(
    val function: FunctionAccessorMetadata<C, Mono<*>>
) : Ordered {
    val include: Set<Class<*>>
    val exclude: Set<Class<*>>
    override val order: Order

    init {
        val afterCommandAnnotation = function.accessor.method.getAnnotation(AfterCommand::class.java)
        if (afterCommandAnnotation == null) {
            include = emptySet()
            exclude = emptySet()
        } else {
            include = afterCommandAnnotation.include.map { it.java }.toSet()
            exclude = afterCommandAnnotation.exclude.map { it.java }.toSet()
        }
        order = function.accessor.method.getAnnotation(Order::class.java) ?: Order()
    }

    /**
     * Determines whether this after-command function should execute for the given command type.
     *
     * The function supports a command if it's not in the exclude set and either the include set is empty
     * (meaning all commands) or the command is in the include set.
     *
     * @param commandType The command class to check.
     * @return true if this function should execute for the command type.
     */
    fun supportCommand(commandType: Class<*>): Boolean {
        if (exclude.contains(commandType)) {
            return false
        }

        if (include.isEmpty()) {
            return true
        }
        return include.contains(commandType)
    }

    companion object {
        /**
         * Converts a function accessor metadata into after-command function metadata.
         *
         * @param C The type of the command aggregate.
         * @return The after-command function metadata.
         */
        fun <C : Any> FunctionAccessorMetadata<C, Mono<*>>.toAfterCommandFunctionMetadata(): AfterCommandFunctionMetadata<C> =
            AfterCommandFunctionMetadata(this)

        /**
         * Converts after-command function metadata into an executable after-command function.
         *
         * @param C The type of the command aggregate.
         * @param commandRoot The command aggregate instance to bind the function to.
         * @return The executable after-command function.
         */
        fun <C : Any> AfterCommandFunctionMetadata<C>.toAfterCommandFunction(commandRoot: C): AfterCommandFunction<C> =
            AfterCommandFunction(this, function.toMessageFunction(commandRoot))
    }
}
