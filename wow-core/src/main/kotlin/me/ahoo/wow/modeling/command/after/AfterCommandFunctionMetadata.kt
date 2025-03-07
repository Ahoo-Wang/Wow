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

import me.ahoo.wow.api.annotation.AfterCommand
import me.ahoo.wow.messaging.function.FunctionAccessorMetadata
import me.ahoo.wow.messaging.function.toMessageFunction
import reactor.core.publisher.Mono

data class AfterCommandFunctionMetadata<C : Any>(val function: FunctionAccessorMetadata<C, Mono<*>>) {
    val include: Set<Class<*>>
    val exclude: Set<Class<*>>

    init {
        val afterCommandAnnotation = function.accessor.method.getAnnotation(AfterCommand::class.java)
        if (afterCommandAnnotation == null) {
            include = emptySet()
            exclude = emptySet()
        } else {
            include = afterCommandAnnotation.include.map { it.java }.toSet()
            exclude = afterCommandAnnotation.exclude.map { it.java }.toSet()
        }
    }

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
        fun <C : Any> FunctionAccessorMetadata<C, Mono<*>>.toAfterCommandFunctionMetadata(): AfterCommandFunctionMetadata<C> {
            return AfterCommandFunctionMetadata(this)
        }

        fun <C : Any> AfterCommandFunctionMetadata<C>.toAfterCommandFunction(commandRoot: C): AfterCommandFunction<C> {
            return AfterCommandFunction(this, function.toMessageFunction(commandRoot))
        }
    }
}
