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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * Names the commands a decide function schedules for an aggregate of another type than its own: the
 * full type name (`context.aggregate.Command`) of the [command] class sent to an aggregate of [target], or null when
 * none is known. The runtime names them from the context's models and the Manifests of the service and its peers.
 */
@InternalWowApi
fun interface CommandTypes {
    fun nameOf(target: AggregateType, command: Class<*>): String?

    /**
     * The aggregate types that decide commands of the class [command]: those of the context's
     * models and of every contract the service knows (its Manifest and the peer contracts on its class path), a
     * mounted command left out. A saga's handler that leaves a command's ref out sends it to the one
     * type this gives; none is a missing contract, several a class it must address in full. None by default.
     */
    fun aggregatesOf(command: Class<*>): List<AggregateType> = emptyList()

    /**
     * The id of the aggregate of [target] that [command] acts on, as its body holds it (`@AggregateId`): read by the
     * context's model, or from the property of its JSON body a contract names. Null when its command type holds none
     * there, or when it leaves it null (a creation). None by default.
     */
    fun aggregateIdOf(target: AggregateType, command: Any): String? = null

    @InternalWowApi
    companion object {
        /** Names none: a decide function schedules commands of its own aggregate type only. */
        @JvmField
        val NONE: CommandTypes = CommandTypes { _, _ -> null }

        /**
         * Names a command by its class's `@Name`, else its simple name, in its target's type (`context.aggregate.Command`),
         * the name a model gives a command unless it says otherwise: for a saga outside a node, in a domain module's test.
         */
        @JvmField
        val SIMPLE_NAMES: CommandTypes = CommandTypes { target, command -> "$target.${typeNameOf(command)}" }
    }
}
